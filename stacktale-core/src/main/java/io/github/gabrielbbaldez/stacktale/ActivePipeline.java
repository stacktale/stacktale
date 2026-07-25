package io.github.gabrielbbaldez.stacktale;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The live pipeline, published for reporters that sit <em>outside</em> the logging path.
 *
 * <p>Every adapter (Logback, Log4j2, JUL) registers its pipeline when it starts and
 * unregisters on stop. A reporter that is not a log appender — {@code stacktale-junit},
 * which turns a failed test into a report — looks the pipeline up here so its reports land
 * in the same file, share the dedup window, and above all can read the <em>story</em>: the
 * events the application logged on that thread before it failed. A reporter that built its
 * own pipeline would see an empty story buffer, because the events were recorded in this
 * one.
 *
 * <p>First active registration wins and is held until it unregisters, so an app with two
 * appenders configured has a stable answer rather than one that changes with start order.
 * Nothing here is required for the logging path to work — an unresolved lookup simply means
 * the caller must fall back to its own pipeline.
 */
public final class ActivePipeline {

    private static final AtomicReference<ReportPipeline> CURRENT = new AtomicReference<>();

    private ActivePipeline() {
    }

    /** Publishes {@code pipeline} if it is usable and no other is currently published. */
    public static void register(ReportPipeline pipeline) {
        if (pipeline == null || !pipeline.isActive()) return;
        CURRENT.compareAndSet(null, pipeline);
    }

    /** Withdraws {@code pipeline}, but only if it is the one currently published. */
    public static void unregister(ReportPipeline pipeline) {
        if (pipeline == null) return;
        CURRENT.compareAndSet(pipeline, null);
    }

    /** The published pipeline, or {@code null} when no adapter is running. */
    public static ReportPipeline current() {
        return CURRENT.get();
    }
}
