package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StoryBufferTest {

    static LogEventData event(String logger, String level, String msg, long ts, Map<String, String> mdc) {
        return event(logger, level, msg, ts, mdc, Thread.currentThread().getName());
    }

    /** Explicit logical thread name — lets a test replay events from many origin threads on one caller. */
    static LogEventData event(String logger, String level, String msg, long ts,
                              Map<String, String> mdc, String threadName) {
        return new LogEventData(ts, level, "ERROR".equals(level), logger,
                threadName, msg, null, msg, mdc, null);
    }

    @Test
    void keepsOnlyLastNEntriesPerThread() {
        StoryBuffer buf = new StoryBuffer(3, 60_000, List.of("traceId"), 200);
        for (int i = 1; i <= 5; i++) buf.record(event("com.acme.A", "INFO", "m" + i, 1000 + i, Map.of()));
        LogEventData err = event("com.acme.A", "ERROR", "boom", 1010, Map.of());
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("m4", "m5", "boom");
        assertThat(story.contextLabel()).startsWith("thread ");
    }

    @Test
    void groupsByCorrelationKeyAcrossThreads() throws Exception {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of("traceId"), 200);
        Map<String, String> mdc = Map.of("traceId", "9f3a");
        Thread t = new Thread(() -> buf.record(event("com.acme.B", "INFO", "from-other-thread", 1000, mdc)));
        t.start();
        t.join();
        LogEventData err = event("com.acme.A", "ERROR", "boom", 1001, mdc);
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("from-other-thread", "boom");
        assertThat(story.contextLabel()).isEqualTo("traceId=9f3a");
    }

    @Test
    void dropsEntriesOutsideTimeWindow() {
        StoryBuffer buf = new StoryBuffer(10, 1_000, List.of(), 200);
        buf.record(event("com.acme.A", "INFO", "old", 1000, Map.of()));
        LogEventData err = event("com.acme.A", "ERROR", "boom", 5000, Map.of());
        buf.record(err);
        Story story = buf.storyFor(err);
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("boom");
        assertThat(story.omittedByAge()).isEqualTo(1); // the aged-out "old" event is accounted for
    }

    @Test
    void truncatesLongMessagesAndShortensLogger() {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of(), 20);
        buf.record(event("com.acme.deep.OrderService", "INFO", "x".repeat(50), 1000, Map.of()));
        LogEventData err = event("com.acme.A", "ERROR", "boom", 1001, Map.of());
        buf.record(err);
        StoryEntry first = buf.storyFor(err).entries().get(0);
        assertThat(first.message()).hasSize(21).endsWith("…");
        assertThat(first.logger()).isEqualTo("OrderService");
    }

    @Test
    void separatesNonCorrelatedStoriesByLogicalThreadName() {
        // #52: under Logback AsyncAppender every event is recorded from ONE physical worker
        // thread, but each keeps its origin threadName. Keying on the logical name (not the
        // physical thread) must keep each request's story separate — not collapse them.
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of("traceId"), 200);
        buf.record(event("com.acme.A", "INFO", "req-1 step", 1000, Map.of(), "http-nio-exec-1"));
        buf.record(event("com.acme.B", "INFO", "req-2 step", 1001, Map.of(), "http-nio-exec-2"));
        LogEventData err1 = event("com.acme.A", "ERROR", "boom-1", 1002, Map.of(), "http-nio-exec-1");
        buf.record(err1);

        Story story = buf.storyFor(err1);
        // only exec-1's own events, even though exec-2's were recorded on the same caller
        assertThat(story.entries()).extracting(StoryEntry::message).containsExactly("req-1 step", "boom-1");
        assertThat(story.contextLabel()).isEqualTo("thread http-nio-exec-1");
    }

    /**
     * A blank name used to fall back to one shared {@code <unnamed>} bucket, so every
     * unnamed virtual thread wrote into the same story and reports carried other requests'
     * log lines. Here the blank name arrives on a *named* caller, which is what a handed-off
     * event looks like (an AsyncAppender worker): the physical thread is not the one that
     * logged, so its id would be a lie. No story beats a wrong one.
     */
    @Test
    void aBlankThreadNameOnANamedCallerYieldsNoStory() {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of("traceId"), 200);
        buf.record(event("com.acme.A", "INFO", "someone else's line", 999, Map.of(), ""));
        LogEventData err = event("com.acme.A", "ERROR", "boom", 1000, Map.of(), "");
        buf.record(err);

        Story story = buf.storyFor(err);
        assertThat(story.entries()).isEmpty();
        assertThat(story.contextLabel()).isEqualTo("thread unidentified");
    }

    /**
     * The regression that matters: two unnamed virtual threads, each logging its own line
     * and then failing. Reached through JUL, Log4j2 and the JUnit listener, which pass
     * {@code Thread.getName()} straight through; Logback synthesizes a name and never
     * arrives blank here.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledForJreRange(
            min = org.junit.jupiter.api.condition.JRE.JAVA_21)
    void unnamedVirtualThreadsDoNotShareAStory() throws Exception {
        StoryBuffer buf = new StoryBuffer(10, 60_000, List.of("traceId"), 200);
        java.util.concurrent.ExecutorService vt = (java.util.concurrent.ExecutorService)
                java.util.concurrent.Executors.class
                        .getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        java.util.Map<Integer, Story> stories = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int id = i;
                fs.add(vt.submit(() -> {
                    ready.countDown();
                    go.await();
                    // an unnamed virtual thread reports "" — the adapters pass that through
                    String blank = Thread.currentThread().getName();
                    buf.record(event("com.acme.A", "INFO", "request " + id, 1000, Map.of(), blank));
                    LogEventData err = event("com.acme.A", "ERROR", "boom " + id, 1001, Map.of(), blank);
                    buf.record(err);
                    stories.put(id, buf.storyFor(err));
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (java.util.concurrent.Future<?> f : fs) f.get();
        } finally {
            vt.shutdown();
        }

        assertThat(stories).hasSize(2);
        stories.forEach((id, story) -> {
            int other = 1 - id;
            assertThat(story.entries()).extracting(StoryEntry::message)
                    .describedAs("story of task %d", id)
                    .contains("request " + id, "boom " + id)
                    .doesNotContain("request " + other, "boom " + other);
        });
    }
}
