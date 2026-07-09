package io.github.gabrielbbaldez.stacktale;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Decides whether an error occurrence deserves a full report, a short repeat summary, or
 * silence. A fingerprint gets a fresh report once per window; repeats within the window
 * are counted and surfaced as throttled summary lines.
 */
final class Deduper {

    private static final int MAX_FINGERPRINTS = 1024;

    private static final class Stats {
        int count;
        long lastReport;
        long lastSummary;
    }

    private final long windowMillis;
    private final long summaryThrottleMillis;
    private final LongSupplier clock;
    private final Map<String, Stats> stats = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Stats> e) {
            return size() > MAX_FINGERPRINTS;
        }
    };

    Deduper(long windowMillis, long summaryThrottleMillis, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.summaryThrottleMillis = summaryThrottleMillis;
        this.clock = clock;
    }

    synchronized Decision decide(String fingerprint) {
        long now = clock.getAsLong();
        Stats s = stats.computeIfAbsent(fingerprint, k -> new Stats());
        if (s.count == 0 || now - s.lastReport > windowMillis) {
            s.count = 1;
            s.lastReport = now;
            s.lastSummary = now;
            return new Decision(Kind.REPORT, 1, now);
        }
        s.count++;
        boolean firstRepeat = s.count == 2;
        if (firstRepeat || now - s.lastSummary >= summaryThrottleMillis) {
            s.lastSummary = now;
            return new Decision(Kind.SUMMARY, s.count, now);
        }
        return new Decision(Kind.SILENT, s.count, now);
    }
}
