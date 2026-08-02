package io.github.gabrielbbaldez.stacktale.junit;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries a test's MDC from the test thread, where it still exists, to the listener, where it
 * does not.
 *
 * <p>{@code TestExecutionListener} is notified after the test method has returned and its
 * {@code @AfterEach} callbacks have run, so by then the MDC is unwound and the synthetic
 * failure event carries no correlation key. It therefore lands in the thread bucket, while
 * everything the test logged went to the {@code traceId} bucket — and the report comes out
 * with a story of one line: itself (#134).
 *
 * <p>{@link StacktaleExtension} runs inside the test's own lifecycle and can see the MDC. It
 * leaves the snapshot here, keyed by unique id rather than thread, because the listener is
 * not guaranteed to be on the thread that ran the test.
 */
final class TestMdc {

    /**
     * Written only for a failing test and removed when read, so the size is bounded by the
     * failures a run has produced but not yet reported — in practice one.
     */
    private static final Map<String, Map<String, String>> BY_UNIQUE_ID = new ConcurrentHashMap<>();

    /** {@code org.slf4j.MDC.getCopyOfContextMap()}, or {@code null} when SLF4J is absent. */
    private static final MethodHandle GET_COPY_OF_CONTEXT_MAP = resolveMdc();

    private TestMdc() {
    }

    private static MethodHandle resolveMdc() {
        try {
            // Reflective for the same reason AgentCaptures resolves CaptureRegistry that way:
            // this module must not require SLF4J. A JUL or Log4j2 project uses the listener
            // with no SLF4J on the classpath at all, and that has to keep working.
            Class<?> mdc = Class.forName("org.slf4j.MDC");
            return MethodHandles.publicLookup().findStatic(
                    mdc, "getCopyOfContextMap", MethodType.methodType(Map.class));
        } catch (Throwable absent) {
            return null;
        }
    }

    /** The current MDC, or an empty map when SLF4J is absent or nothing is set. */
    @SuppressWarnings("unchecked")
    static Map<String, String> snapshot() {
        if (GET_COPY_OF_CONTEXT_MAP == null) return Map.of();
        try {
            Map<String, String> current = (Map<String, String>) GET_COPY_OF_CONTEXT_MAP.invoke();
            return current == null || current.isEmpty() ? Map.of() : Map.copyOf(current);
        } catch (Throwable ignored) {
            return Map.of(); // a story without the correlation key beats failing a test run
        }
    }

    static void remember(String uniqueId, Map<String, String> mdc) {
        if (uniqueId == null || mdc == null || mdc.isEmpty()) return;
        BY_UNIQUE_ID.put(uniqueId, mdc);
    }

    /** Reads and clears — a snapshot belongs to exactly one report. */
    static Map<String, String> take(String uniqueId) {
        if (uniqueId == null) return Map.of();
        Map<String, String> mdc = BY_UNIQUE_ID.remove(uniqueId);
        return mdc == null ? Map.of() : mdc;
    }

    /** Whether SLF4J was found — reported in the extension's own failure mode, not guessed at. */
    static boolean mdcAvailable() {
        return GET_COPY_OF_CONTEXT_MAP != null;
    }
}
