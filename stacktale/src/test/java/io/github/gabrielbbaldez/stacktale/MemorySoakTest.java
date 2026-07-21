package io.github.gabrielbbaldez.stacktale;

import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Memory soak (issue #37). Not a CI test — it is gated behind a system property and skipped
 * by every normal build. It hammers the full Logback → pipeline → writer path with the
 * churn that fills stacktale's bounded state (rotating thread names, distinct correlation
 * ids, and both recurring and ever-distinct fingerprints, with the file rotating
 * constantly), sampling the live heap and asserting it does not grow monotonically.
 *
 * <p>Everything the pipeline retains is bounded by design — the dedup stats
 * ({@code MAX_FINGERPRINTS}), the per-correlation and per-thread-name story maps
 * ({@code MAX_CONTEXTS}), and the per-thread last-report map — so a flat heap under endless
 * distinct keys is the property under test. A leak would show as a rising post-GC floor.
 *
 * <p>Run it:
 * <pre>
 *   mvn -q -pl stacktale test -Dstacktale.soak=true \
 *       -Dtest=MemorySoakTest -Dstacktale.soak.seconds=3600
 * </pre>
 * Default duration is 60s; the committed run in {@code docs/soak.md} used a longer window.
 */
@EnabledIfSystemProperty(named = "stacktale.soak", matches = "true")
class MemorySoakTest {

    // A fixed throwable so its fingerprint is stable across the whole run (the dedup path).
    private static final RuntimeException RECURRING = recurring();

    @Test
    void heapDoesNotGrowUnderSustainedChurn(@TempDir Path dir) throws Exception {
        long seconds = Long.getLong("stacktale.soak.seconds", 60L);

        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(dir.resolve("errors-ai.log").toString());
        appender.setMaxFileSizeMb(1); // tiny cap → the file rotates constantly under load
        appender.setMaxBackups(2);
        appender.setInstallUncaughtHandler(false);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);

        List<Long> samples = new ArrayList<>();
        AtomicLong events = new AtomicLong();
        long endAt = System.nanoTime() + seconds * 1_000_000_000L;
        long nextSampleAt = System.nanoTime() + 2_000_000_000L;
        long id = 0;

        while (System.nanoTime() < endAt) {
            // A burst of short-lived, uniquely named threads: distinct thread names churn the
            // per-thread story/last-report maps, distinct traceIds churn the correlation map,
            // and an ever-distinct fingerprint churns the dedup map — all bounded.
            List<Thread> batch = new ArrayList<>();
            for (int t = 0; t < 150; t++) {
                long n = id++;
                Thread worker = new Thread(() -> {
                    MDC.put("traceId", "tr-" + n);
                    try {
                        Logger app = ctx.getLogger("com.acme.Svc" + (n % 64));
                        app.info("GET /op/{}", n); // story context
                        app.error("recurring downstream failure", RECURRING); // dedup hit
                        RuntimeException distinct = new RuntimeException("distinct failure " + n);
                        distinct.setStackTrace(new StackTraceElement[]{
                                new StackTraceElement("com.acme.Svc", "m" + (n % 8192), "Svc.java",
                                        (int) (n % 8192))});
                        app.error("op {} failed", n, distinct); // ever-distinct fingerprint
                        events.incrementAndGet();
                    } finally {
                        MDC.clear();
                    }
                }, "worker-" + n);
                batch.add(worker);
                worker.start();
            }
            for (Thread worker : batch) worker.join();

            if (System.nanoTime() >= nextSampleAt) {
                samples.add(usedHeapAfterGc());
                nextSampleAt = System.nanoTime() + 2_000_000_000L;
            }
        }
        appender.stop();

        assertThat(samples.size())
                .as("need enough samples to judge a trend — raise the duration")
                .isGreaterThanOrEqualTo(6);

        // Drop the first sample (JIT / class-load warm-up) and compare the mean live heap of
        // the first third against the last third. Bounded state ⇒ the floor stays flat; a
        // leak would push the last third well above the first.
        List<Long> steady = samples.subList(1, samples.size());
        int third = steady.size() / 3;
        double firstThird = mean(steady.subList(0, third));
        double lastThird = mean(steady.subList(steady.size() - third, steady.size()));
        double ratio = lastThird / firstThird;

        System.out.printf(
                "SOAK: %ds, %d events, %d samples, firstThird=%.1fMB lastThird=%.1fMB ratio=%.2f%n",
                seconds, events.get(), samples.size(),
                firstThird / 1e6, lastThird / 1e6, ratio);

        assertThat(ratio)
                .as("live heap must not grow monotonically (%.2fx over the run)", ratio)
                .isLessThan(1.5);
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(80);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static double mean(List<Long> xs) {
        return xs.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static RuntimeException recurring() {
        RuntimeException e = new RuntimeException("recurring downstream failure");
        e.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.Downstream", "call", "Downstream.java", 42)});
        return e;
    }
}
