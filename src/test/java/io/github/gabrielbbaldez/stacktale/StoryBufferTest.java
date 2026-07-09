package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StoryBufferTest {

    static ILoggingEvent event(String logger, Level level, String msg, long ts, Map<String, String> mdc) {
        LoggerContext ctx = new LoggerContext();
        Logger l = ctx.getLogger(logger);
        LoggingEvent e = new LoggingEvent("fqcn", l, level, msg, null, null);
        e.setTimeStamp(ts);
        e.setMDCPropertyMap(mdc);
        return e;
    }

    @Test
    void keepsOnlyLastNEntriesPerThread() {
        StoryBuffer buf = new StoryBuffer(3, 60_000, List.of("traceId"), 200);
        for (int i = 1; i <= 5; i++) buf.record(event("com.acme.A", Level.INFO, "m" + i, 1000 + i, Map.of()));
        ILoggingEvent err = event("com.acme.A", Level.ERROR, "boom", 1010, Map.of());
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("m4", "m5", "boom");
        assertThat(story.contextLabel()).startsWith("thread ");
    }

    @Test
    void groupsByCorrelationKeyAcrossThreads() throws Exception {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of("traceId"), 200);
        Map<String, String> mdc = Map.of("traceId", "9f3a");
        Thread t = new Thread(() -> buf.record(event("com.acme.B", Level.INFO, "from-other-thread", 1000, mdc)));
        t.start();
        t.join();
        ILoggingEvent err = event("com.acme.A", Level.ERROR, "boom", 1001, mdc);
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("from-other-thread", "boom");
        assertThat(story.contextLabel()).isEqualTo("traceId=9f3a");
    }

    @Test
    void dropsEntriesOutsideTimeWindow() {
        StoryBuffer buf = new StoryBuffer(10, 1_000, List.of(), 200);
        buf.record(event("com.acme.A", Level.INFO, "old", 1000, Map.of()));
        ILoggingEvent err = event("com.acme.A", Level.ERROR, "boom", 5000, Map.of());
        buf.record(err);
        assertThat(buf.storyFor(err).entries()).extracting(StoryEntry::message).containsExactly("boom");
    }

    @Test
    void truncatesLongMessagesAndShortensLogger() {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of(), 20);
        buf.record(event("com.acme.deep.OrderService", Level.INFO, "x".repeat(50), 1000, Map.of()));
        ILoggingEvent err = event("com.acme.A", Level.ERROR, "boom", 1001, Map.of());
        buf.record(err);
        StoryEntry first = buf.storyFor(err).entries().get(0);
        assertThat(first.message()).hasSize(21).endsWith("…");
        assertThat(first.logger()).isEqualTo("OrderService");
    }
}
