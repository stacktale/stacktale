package io.github.gabrielbbaldez.stacktale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Funnels uncaught exceptions through the normal logging pipeline (logger
 * {@code stacktale.uncaught}), so plain-Java apps get reports for exceptions that never
 * reach a {@code log.error}. Wraps and preserves any pre-existing default handler.
 */
final class UncaughtHandler implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler previous;
    private final Logger logger;

    UncaughtHandler(Thread.UncaughtExceptionHandler previous, Logger logger) {
        this.previous = previous;
        this.logger = logger;
    }

    static void install() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof UncaughtHandler) return; // idempotent
        Thread.setDefaultUncaughtExceptionHandler(
                new UncaughtHandler(current, LoggerFactory.getLogger("stacktale.uncaught")));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            logger.error("Uncaught exception in thread {}", t.getName(), e);
        } catch (Throwable ignored) {
            // never make an uncaught exception worse
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        } else if (t.getThreadGroup() != null) {
            t.getThreadGroup().uncaughtException(t, e); // JVM default behavior: print to stderr
        }
    }
}
