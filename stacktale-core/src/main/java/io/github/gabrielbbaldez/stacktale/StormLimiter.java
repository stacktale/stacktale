package io.github.gabrielbbaldez.stacktale;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Global rate limit on full reports. Dedup handles the SAME error repeating; a cascade
 * failure instead produces hundreds of DISTINCT fingerprints per minute (every request
 * fails differently as dependencies collapse), and without a ceiling that floods the file
 * and rotates useful history away exactly when it's needed. Beyond the limit, reports are
 * counted and surfaced as a single throttled storm line instead of full blocks.
 */
final class StormLimiter {

    /** What to do with a would-be full report. */
    enum Action { ALLOW, SUPPRESS, STORM_LINE }

    record Outcome(Action action, int suppressed) {
        static final Outcome ALLOW = new Outcome(Action.ALLOW, 0);
        static final Outcome SUPPRESS = new Outcome(Action.SUPPRESS, 0);
    }

    private final int maxPerWindow;
    private final long windowMillis;
    private final long stormLineThrottleMillis;
    private final LongSupplier clock;

    private final Deque<Long> reportTimes = new ArrayDeque<>();
    private int suppressedSinceLine;
    private boolean everEmittedStormLine;
    private long lastStormLine;

    StormLimiter(int maxPerWindow, long windowMillis, long stormLineThrottleMillis, LongSupplier clock) {
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
        this.stormLineThrottleMillis = stormLineThrottleMillis;
        this.clock = clock;
    }

    /** Disabled limiter: always allows. */
    static StormLimiter disabled() {
        return new StormLimiter(0, 0, 0, () -> 0L);
    }

    private boolean enabled() {
        return maxPerWindow > 0;
    }

    synchronized Outcome onReport() {
        if (!enabled()) return Outcome.ALLOW;
        long now = clock.getAsLong();
        while (!reportTimes.isEmpty() && now - reportTimes.peekFirst() > windowMillis) {
            reportTimes.pollFirst();
        }
        if (reportTimes.size() < maxPerWindow) {
            reportTimes.addLast(now);
            return Outcome.ALLOW;
        }
        // over the limit: count it, and emit a storm line on the first suppression and
        // at most once per throttle window thereafter
        suppressedSinceLine++;
        if (!everEmittedStormLine || now - lastStormLine >= stormLineThrottleMillis) {
            everEmittedStormLine = true;
            lastStormLine = now;
            int count = suppressedSinceLine;
            suppressedSinceLine = 0;
            return new Outcome(Action.STORM_LINE, count);
        }
        return Outcome.SUPPRESS;
    }

    /** Suppressed reports not yet reflected in a storm line — flushed on shutdown. */
    synchronized int drainSuppressed() {
        int count = suppressedSinceLine;
        suppressedSinceLine = 0;
        return count;
    }

    int maxPerWindow() {
        return maxPerWindow;
    }
}
