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
    private static final MethodHandle GET = resolve();

    private AgentCaptures() {}

    private static MethodHandle resolve() {
        try {
            Class<?> registry = Class.forName(
                    "io.github.gabrielbbaldez.stacktale.agent.CaptureRegistry",
                    false, ClassLoader.getSystemClassLoader());
            return MethodHandles.publicLookup().findStatic(registry, "get",
                    MethodType.methodType(List.class, Throwable.class));
        } catch (Throwable absent) {
            return null; // agent not attached — captures are simply empty
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
