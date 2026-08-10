package io.github.gabrielbbaldez.stacktale;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridge to the optional {@code stacktale-agent}: when the agent is attached, its
 * {@code CaptureRegistry} holds throw-site method arguments per throwable. The core
 * reads it reflectively — no dependency, no cost when the agent is absent (a single
 * cached lookup miss).
 */
final class AgentCaptures {

    private static final int MAX_LINES = 8;
    private static final MethodHandle GET = resolve("get");
    private static final MethodHandle REPRO = resolve("repro");

    private AgentCaptures() {}

    /**
     * Each entry point is looked up by name, separately. An agent jar older than this core
     * has no {@code repro} method, and resolving them together would cost the captures too —
     * the two versions are released as one project but a user can pin them apart.
     */
    private static MethodHandle resolve(String name) {
        try {
            Class<?> registry = Class.forName(
                    "io.github.gabrielbbaldez.stacktale.agent.CaptureRegistry",
                    false, ClassLoader.getSystemClassLoader());
            return MethodHandles.publicLookup().findStatic(registry, name,
                    MethodType.methodType(List.class, Throwable.class));
        } catch (Throwable absent) {
            return null; // agent not attached, or too old for this entry point
        }
    }

    /**
     * The innermost captured frame as a reproduction seed, or {@code null} when the agent is
     * absent, older, or captured nothing.
     *
     * <p>Parses the agent's wire format — {@code m <fqcn> <method>} then {@code p <type>
     * <name> <value>} — stopping at the second {@code m}. Fields are space-separated with the
     * value taking the remainder of the line, so a value containing spaces survives; types and
     * names cannot contain any.
     */
    @SuppressWarnings("unchecked")
    static ReproSeed seedFor(Throwable throwable) {
        if (REPRO == null || throwable == null) return null;
        try {
            return parseSeed((List<String>) REPRO.invoke(throwable));
        } catch (Throwable t) {
            return null; // a seed is a bonus; never let it cost the report
        }
    }

    /**
     * Package-private and taking the lines directly, so the wire format can be tested without
     * an agent attached — the parser is the half most likely to drift from the emitter, and
     * the two live in different modules that a user can pin to different versions.
     */
    static ReproSeed parseSeed(List<String> lines) {
        try {
            if (lines == null || lines.isEmpty()) return null;
            String className = null;
            String methodName = null;
            List<ReproSeed.Param> params = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("m ")) {
                    if (className != null) break; // second frame: the seed is the innermost
                    String[] parts = line.split(" ", 3);
                    if (parts.length < 3) return null;
                    className = parts[1];
                    methodName = parts[2];
                } else if (line.startsWith("p ") && className != null) {
                    String[] parts = line.split(" ", 4);
                    // a parameter with an empty value still splits into 4 with a trailing ""
                    if (parts.length < 4) continue;
                    params.add(new ReproSeed.Param(parts[1], parts[2], parts[3]));
                }
            }
            return className == null ? null : new ReproSeed(className, methodName, List.copyOf(params));
        } catch (Throwable t) {
            return null; // a seed is a bonus; never let it cost the report
        }
    }

    /** Captured frames for the whole cause chain, outermost first, deduped and bounded. */
    @SuppressWarnings("unchecked")
    static List<String> forChain(Throwable throwable) {
        if (GET == null || throwable == null) return List.of();
        try {
            Set<String> lines = new LinkedHashSet<>();
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Throwable cur = throwable;
            while (cur != null && seen.add(cur) && seen.size() <= 10 && lines.size() < MAX_LINES) {
                lines.addAll((List<String>) GET.invoke(cur));
                cur = cur.getCause();
            }
            List<String> result = new ArrayList<>(lines);
            return result.size() > MAX_LINES ? result.subList(0, MAX_LINES) : result;
        } catch (Throwable t) {
            return List.of();
        }
    }
}
