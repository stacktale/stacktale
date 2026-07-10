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

    private static final int MAX_FRAMES_PER_THROWABLE = 5;
    private static final int MAX_VALUE_LENGTH = 60;

    private static final Map<Throwable, Deque<String>> CAPTURES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, String[]> PARAMETER_NAMES = new ConcurrentHashMap<>();

    private CaptureRegistry() {}

    /** Called from instrumented methods (via advice) when they exit with a throwable. */
    public static void record(Throwable thrown, String className, String methodName, Object[] args) {
        try {
            Deque<String> frames = CAPTURES.computeIfAbsent(thrown, k -> new ArrayDeque<>());
            synchronized (frames) {
                if (frames.size() >= MAX_FRAMES_PER_THROWABLE) return;
                frames.addLast(formatFrame(className, methodName, args));
            }
        } catch (Throwable ignored) {
            // never make a failing app worse
        }
    }

    /** Read by stacktale-core via reflection. */
    public static List<String> get(Throwable thrown) {
        Deque<String> frames = CAPTURES.get(thrown);
        if (frames == null) return List.of();
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    private static String formatFrame(String className, String methodName, Object[] args) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        StringBuilder sb = new StringBuilder(simple).append('.').append(methodName).append('(');
        String[] names = parameterNames(className, methodName, args == null ? 0 : args.length);
        for (int i = 0; args != null && i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names != null && i < names.length ? names[i] : "arg" + i).append('=').append(render(args[i]));
        }
        return sb.append(')').toString();
    }

    private static String render(Object value) {
        try {
            if (value == null) return "null";
            if (value.getClass().isArray()) {
                return value.getClass().getComponentType().getSimpleName()
                        + "[" + java.lang.reflect.Array.getLength(value) + "]";
            }
            String s = String.valueOf(value);
            return s.length() > MAX_VALUE_LENGTH ? s.substring(0, MAX_VALUE_LENGTH) + "…" : s;
        } catch (Throwable t) {
            return "<toString failed>";
        }
    }

    /** Real parameter names when the class was compiled with -parameters; argN otherwise. */
    private static String[] parameterNames(String className, String methodName, int argCount) {
        String key = className + '#' + methodName + '#' + argCount;
        return PARAMETER_NAMES.computeIfAbsent(key, k -> {
            try {
                Class<?> cls = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
                Method match = null;
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals(methodName) && m.getParameterCount() == argCount) {
                        if (match != null) return new String[0]; // overload ambiguity — fall back to argN
                        match = m;
                    }
                }
                if (match == null) return new String[0];
                Parameter[] parameters = match.getParameters();
                String[] names = new String[parameters.length];
                for (int i = 0; i < parameters.length; i++) names[i] = parameters[i].getName();
                return names;
            } catch (Throwable t) {
                return new String[0];
            }
        });
    }
}
