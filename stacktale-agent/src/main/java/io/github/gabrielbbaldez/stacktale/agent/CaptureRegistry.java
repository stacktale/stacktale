package io.github.gabrielbbaldez.stacktale.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the agent parks what it saw at the throw site: for each in-flight throwable, the
 * method frames it escaped through with their argument values. The core library reads
 * this reflectively (it never depends on the agent) and renders the {@code captured:}
 * section. Weak keys: entries die with the throwable. Everything here is bounded and
 * exception-proof — the agent must never make a failing app worse.
 */
public final class CaptureRegistry {

    private static volatile int maxFrames = 5;
    private static volatile int maxValueLength = 60;
    private static volatile boolean renderToString = true;

    private static final Map<Throwable, Deque<Frame>> CAPTURES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, String[]> PARAMETER_NAMES = new ConcurrentHashMap<>();
    private static final Map<String, String[]> PARAMETER_TYPES = new ConcurrentHashMap<>();

    /**
     * One instrumented frame, kept structured rather than pre-rendered.
     *
     * <p>The {@code captured:} section wants a compact line; a repro seed wants the
     * fully-qualified class and the <em>declared</em> parameter types, which is what makes
     * the signature reconstructable. Recording once and rendering per reader keeps the
     * throw path doing no extra work — reads only happen when a report is written.
     */
    private record Frame(String className, String methodName,
                         String[] types, String[] names, String[] values) {}

    private CaptureRegistry() {}

    /** Applied once at agent install from the {@code -javaagent} arguments. */
    public static void configure(int frames, int valueLength, boolean toString) {
        maxFrames = Math.max(1, frames);
        maxValueLength = Math.max(8, valueLength);
        renderToString = toString;
    }

    /** Called from instrumented methods (via advice) when they exit with a throwable. */
    public static void record(Throwable thrown, String className, String methodName, Object[] args) {
        try {
            Deque<Frame> frames = CAPTURES.computeIfAbsent(thrown, k -> new ArrayDeque<>());
            synchronized (frames) {
                if (frames.size() >= maxFrames) return;
                frames.addLast(frameOf(className, methodName, args));
            }
        } catch (Throwable ignored) {
            // never make a failing app worse
        }
    }

    /** Read by stacktale-core via reflection. Renders the compact {@code captured:} line. */
    public static List<String> get(Throwable thrown) {
        List<String> out = new ArrayList<>();
        for (Frame f : snapshot(thrown)) {
            String simple = f.className().substring(f.className().lastIndexOf('.') + 1);
            StringBuilder sb = new StringBuilder(simple).append('.').append(f.methodName()).append('(');
            for (int i = 0; i < f.values().length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(f.names()[i]).append('=').append(f.values()[i]);
            }
            out.add(sb.append(')').toString());
        }
        return out;
    }

    /**
     * Read by stacktale-core via reflection, separately from {@link #get}. A second method
     * rather than a wider return type on the first: the core resolves each by name and
     * signature, so an older agent paired with a newer core loses the repro seed and keeps
     * its captures, instead of losing both to a failed lookup.
     *
     * <p>Wire format, one element per line, fields space-separated with the value taking
     * the remainder — types and names never contain spaces, values may contain anything:
     *
     * <pre>
     * m com.acme.shop.PaymentService charge
     * p long orderId 889
     * p java.math.BigDecimal amount 149.90
     * </pre>
     */
    public static List<String> repro(Throwable thrown) {
        List<String> out = new ArrayList<>();
        for (Frame f : snapshot(thrown)) {
            out.add("m " + f.className() + ' ' + f.methodName());
            for (int i = 0; i < f.values().length; i++) {
                out.add("p " + f.types()[i] + ' ' + f.names()[i] + ' ' + f.values()[i]);
            }
        }
        return out;
    }

    private static List<Frame> snapshot(Throwable thrown) {
        Deque<Frame> frames = CAPTURES.get(thrown);
        if (frames == null) return List.of();
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    private static Frame frameOf(String className, String methodName, Object[] args) {
        int n = args == null ? 0 : args.length;
        String[] names = parameterNames(className, methodName, n);
        String[] types = parameterTypes(className, methodName, n);
        String[] outNames = new String[n];
        String[] outTypes = new String[n];
        String[] outValues = new String[n];
        for (int i = 0; i < n; i++) {
            outNames[i] = names != null && i < names.length ? names[i] : "arg" + i;
            // the declared type when reflection could resolve the method; otherwise the
            // runtime class, which still names something an agent can write a call against
            outTypes[i] = types != null && i < types.length ? types[i]
                    : (args[i] == null ? "java.lang.Object" : args[i].getClass().getName());
            outValues[i] = render(args[i]);
        }
        return new Frame(className, methodName, outTypes, outNames, outValues);
    }

    /** Declared parameter types, so a repro seed can name a signature rather than guess it. */
    private static String[] parameterTypes(String className, String methodName, int argCount) {
        String key = className + '#' + methodName + '#' + argCount;
        return PARAMETER_TYPES.computeIfAbsent(key, k -> {
            Method match = resolve(className, methodName, argCount);
            if (match == null) return new String[0];
            Class<?>[] declared = match.getParameterTypes();
            String[] types = new String[declared.length];
            for (int i = 0; i < declared.length; i++) types[i] = declared[i].getTypeName();
            return types;
        });
    }

    private static String render(Object value) {
        try {
            if (value == null) return "null";
            if (value.getClass().isArray()) {
                return value.getClass().getComponentType().getSimpleName()
                        + "[" + java.lang.reflect.Array.getLength(value) + "]";
            }
            // privacy mode: for non-value types, record the type name only, never the
            // toString() (which may hold PII). Primitives/wrappers/String/enum are shown.
            if (!renderToString && !isValueType(value.getClass())) {
                return value.getClass().getSimpleName();
            }
            String s = String.valueOf(value);
            return s.length() > maxValueLength ? s.substring(0, maxValueLength) + "…" : s;
        } catch (Throwable t) {
            return "<toString failed>";
        }
    }

    private static boolean isValueType(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class
                || Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class;
    }

    /** Real parameter names when the class was compiled with -parameters; argN otherwise. */
    private static String[] parameterNames(String className, String methodName, int argCount) {
        String key = className + '#' + methodName + '#' + argCount;
        return PARAMETER_NAMES.computeIfAbsent(key, k -> {
            Method match = resolve(className, methodName, argCount);
            if (match == null) return new String[0];
            Parameter[] parameters = match.getParameters();
            String[] names = new String[parameters.length];
            for (int i = 0; i < parameters.length; i++) names[i] = parameters[i].getName();
            return names;
        });
    }

    /**
     * The declared method behind an instrumented frame, or {@code null} when it cannot be
     * named unambiguously. An overload with the same arity is left unresolved on purpose:
     * guessing between two signatures would put a wrong type in a repro seed, which is
     * worse than putting none.
     */
    private static Method resolve(String className, String methodName, int argCount) {
        try {
            Class<?> cls = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            Method match = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == argCount) {
                    if (match != null) return null; // overload ambiguity
                    match = m;
                }
            }
            return match;
        } catch (Throwable t) {
            return null;
        }
    }
}
