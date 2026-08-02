package io.github.gabrielbbaldez.stacktale;

import java.util.function.BiConsumer;

/**
 * Funnels uncaught exceptions through the normal logging pipeline (logger
 * {@code stacktale.uncaught}), so plain-Java apps get reports for exceptions that never
 * reach a {@code log.error}. Wraps and preserves any pre-existing default handler.
 *
 * <p>The sink is provided by the logging backend ("log this message + throwable at ERROR
 * through logger {@code stacktale.uncaught}") so the handler works identically for
 * Logback and Log4j2 hosts.
 */
public final class UncaughtHandler implements Thread.UncaughtExceptionHandler {

    /** Logger name backends must route the sink through — the pipeline processes it normally. */
    public static final String UNCAUGHT_LOGGER = "stacktale.uncaught";

    private final Thread.UncaughtExceptionHandler previous;
    private final BiConsumer<String, Throwable> errorSink;

    UncaughtHandler(Thread.UncaughtExceptionHandler previous, BiConsumer<String, Throwable> errorSink) {
        this.previous = previous;
        this.errorSink = errorSink;
    }

    /**
     * Routes uncaught exceptions to {@code errorSink}, which logs (message, throwable) at
     * ERROR via logger {@link #UNCAUGHT_LOGGER}.
     *
     * <p>An existing handler of ours is <em>replaced</em>, not left in place. It used to be
     * left, and the effect was that after a Spring DevTools restart — or the second context
     * in a test suite — the stale handler kept feeding a pipeline that had been closed, so no
     * uncaught exception produced a report ever again. The stale instance's {@code previous}
     * is carried forward so whatever sat below us in the chain still runs.
     *
     * <p>Last caller wins. Two live pipelines both wanting uncaught exceptions is not a
     * situation with a right answer, and preferring the newest is what makes a restart work.
     */
    public static void install(BiConsumer<String, Throwable> errorSink) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler below =
                current instanceof UncaughtHandler ours ? ours.previous : current;
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtHandler(below, errorSink));
    }

    /**
     * Restores the handler that was in place before ours, if ours is still the default.
     *
     * <p>Without this the static default handler pins a dead {@code LoggerContext} — and in a
     * servlet hot-redeploy, the whole webapp classloader behind it.
     *
     * <p>Deliberately does nothing when the current default is not ours: an application that
     * installed its own handler after us owns that slot, and taking it back would be worse
     * than the leak.
     */
    public static void uninstall() {
        if (Thread.getDefaultUncaughtExceptionHandler() instanceof UncaughtHandler ours) {
            Thread.setDefaultUncaughtExceptionHandler(ours.previous);
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            errorSink.accept("Uncaught exception in thread " + t.getName(), e);
        } catch (Throwable ignored) {
            // never make an uncaught exception worse
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
            return;
        }
        // Deliberately NOT t.getThreadGroup().uncaughtException(t, e).
        //
        // The JVM dispatches through Thread.getUncaughtExceptionHandler(), which is the
        // thread's own handler or its ThreadGroup; the group walks up to the root, and the
        // root's last act is to call Thread.getDefaultUncaughtExceptionHandler(). That is us.
        // So by the time this runs the group chain has already had its turn, and handing the
        // throwable back to it means root → default → here → root, until the stack ends.
        //
        // With no previous handler — the ordinary case, since most applications never set one
        // — every uncaught exception hit that loop and died as a StackOverflowError instead of
        // being printed. Nothing caught it because the only test built the handler by hand
        // rather than through install(), leaving the JVM default null and the loop unclosed.
        //
        // What the root group does when there is no default handler, done here instead:
        if (!(e instanceof ThreadDeath)) {
            System.err.print("Exception in thread \"" + t.getName() + "\" ");
            e.printStackTrace(System.err);
        }
    }
}
