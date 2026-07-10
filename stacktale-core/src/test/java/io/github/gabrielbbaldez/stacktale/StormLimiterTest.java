package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class StormLimiterTest {

    @Test
    void allowsUpToTheLimitThenSuppressesWithAStormLine() {
        AtomicLong now = new AtomicLong(0);
        StormLimiter limiter = new StormLimiter(3, 60_000, 10_000, now::get);

        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.ALLOW);
        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.ALLOW);
        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.ALLOW);
        // 4th within the window: over the limit → first storm line, carrying the count
        StormLimiter.Outcome fourth = limiter.onReport();
        assertThat(fourth.action()).isEqualTo(StormLimiter.Action.STORM_LINE);
        assertThat(fourth.suppressed()).isEqualTo(1);
        // further ones inside the storm-line throttle stay silent
        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.SUPPRESS);
        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.SUPPRESS);
    }

    @Test
    void emitsAnotherStormLineAfterTheThrottle() {
        AtomicLong now = new AtomicLong(0);
        StormLimiter limiter = new StormLimiter(1, 60_000, 10_000, now::get);
        limiter.onReport();                       // ALLOW (fills the limit)
        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.STORM_LINE);
        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.SUPPRESS);
        now.set(10_000);                          // throttle elapsed
        StormLimiter.Outcome next = limiter.onReport();
        assertThat(next.action()).isEqualTo(StormLimiter.Action.STORM_LINE);
        assertThat(next.suppressed()).isEqualTo(2); // the 2 suppressed since the last line
    }

    @Test
    void recoversWhenTheWindowSlidesPast() {
        AtomicLong now = new AtomicLong(0);
        StormLimiter limiter = new StormLimiter(2, 60_000, 10_000, now::get);
        limiter.onReport();
        limiter.onReport();
        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.STORM_LINE); // over limit
        now.set(60_001);                           // old reports aged out
        assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.ALLOW);
    }

    @Test
    void disabledAlwaysAllows() {
        StormLimiter limiter = StormLimiter.disabled();
        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.onReport().action()).isEqualTo(StormLimiter.Action.ALLOW);
        }
    }

    @Test
    void drainSuppressedReportsLeftoverCount() {
        AtomicLong now = new AtomicLong(0);
        StormLimiter limiter = new StormLimiter(1, 60_000, 10_000, now::get);
        limiter.onReport();                       // ALLOW
        limiter.onReport();                       // STORM_LINE (resets counter)
        limiter.onReport();                       // SUPPRESS (counter = 1)
        assertThat(limiter.drainSuppressed()).isEqualTo(1);
        assertThat(limiter.drainSuppressed()).isZero(); // idempotent
    }
}
