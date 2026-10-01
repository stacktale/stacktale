package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A broken shipper on {@code stacktale.reports} must not cost anyone a report.
 *
 * <p>With {@code emitReportsToLogger=true} the block is handed to the host after it is on
 * disk. JUL propagates a throwing {@code Handler.publish} through {@code Logger.info}, and
 * Log4j2 throws {@code AppenderLoggingException} from an appender with
 * {@code ignoreExceptions=false} — so {@code emitReport} can throw. When it did, the throw
 * reached the write-path failure counter: five reports later the pipeline parked itself,
 * blaming the file it had just written to successfully, and stopped reporting for the rest
 * of the run. The pointer line on logger {@code stacktale} runs at the same spot and had the
 * same flaw.
 */
class ShipperFailureTest {

    /** A host whose self-log and/or shipper throw, recording what it was told. */
    private static class BrokenHost implements ReportPipeline.Host {
        private final boolean selfLogThrows;
        private final boolean emitThrows;
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final List<String> emitted = new CopyOnWriteArrayList<>();
        final AtomicInteger emitAttempts = new AtomicInteger();

        BrokenHost(boolean selfLogThrows, boolean emitThrows) {
            this.selfLogThrows = selfLogThrows;
            this.emitThrows = emitThrows;
        }

        @Override
        public void selfLog(String message) {
            if (selfLogThrows) throw new IllegalStateException("console handler down");
        }

        @Override
        public void warn(String message, Throwable t) {
            warnings.add(message);
        }

        @Override
        public void emitReport(String block) {
            emitAttempts.incrementAndGet();
            if (emitThrows) throw new IllegalStateException("shipper down");
            emitted.add(block);
        }
    }

    /** Distinct culprit frame per marker, so each one is its own fingerprint. */
    private static LogEventData error(String marker, long epochMillis) {
        Throwable t = new IllegalStateException(marker);
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example." + marker, "run", marker + ".java", 42)
        });
        return new LogEventData(epochMillis, "ERROR", true, "com.example.Logger", "main",
                "failed: " + marker, new Object[0], "failed: " + marker, Map.of(), t);
    }

    private static int reportCount(Path file) throws Exception {
        int n = 0;
        for (String line : Files.readString(file).split("\n", -1)) {
            if (line.startsWith("━━━ ERROR #")) n++;
        }
        return n;
    }

    private record Fixture(ReportPipeline pipeline, BrokenHost host, Path file, AtomicLong clock) {
        void logDistinctErrors(int n) {
            for (int i = 0; i < n; i++) {
                pipeline.process(error("err" + i, clock.get()));
            }
        }
    }

    private static Fixture fixture(Path dir, BrokenHost host) {
        Path file = dir.resolve("errors-ai.log");
        ReportPipeline.Settings settings = ReportPipeline.Settings.builder()
                .file(file.toString())
                .appName("test")
                .appVersion("0")
                .echoSuppressionMillis(0)
                .maxReportsPerMinute(0)
                .emitReportsToLogger(true)
                .zone(ZoneId.of("UTC"))
                .build();
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        Renderer renderer = new ReportRenderer(settings.zone(), Redactor.disabled());
        ReportWriter writer = new ReportWriter(file, settings.maxFileBytes(), renderer.fileHeader(),
                null, false, settings.maxBackups(), host::warn);
        return new Fixture(ReportPipeline.forTesting(settings, host, writer, renderer, clock::get),
                host, file, clock);
    }

    @Test
    void aThrowingShipperNeverParksThePipeline(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir, new BrokenHost(false, true));

        f.logDistinctErrors(12); // well past MAX_CONSECUTIVE_FAILURES (5)

        ReportPipeline.Stats stats = f.pipeline().stats();
        assertThat(stats.parked()).isFalse();
        // the write path never failed; a shipper is not the write path
        assertThat(stats.failures()).isZero();
        assertThat(stats.reportsWritten()).isEqualTo(12);
        assertThat(reportCount(f.file())).isEqualTo(12);
        // every block is still offered: a shipper that recovers must get the next one
        assertThat(f.host().emitAttempts).hasValue(12);
    }

    @Test
    void aThrowingShipperIsWarnedOnceAndNamedAsTheShipperNotTheFile(@TempDir Path dir) {
        Fixture f = fixture(dir, new BrokenHost(false, true));

        f.logDistinctErrors(12);

        // one warning, not one per report: a dead shipper must not flood the status channel
        assertThat(f.host().warnings).hasSize(1);
        String warning = f.host().warnings.get(0);
        assertThat(warning).contains(ReportPipeline.REPORTS_LOGGER);
        assertThat(warning).doesNotContain("parked");
        assertThat(warning).doesNotContain("failures writing");
    }

    @Test
    void aThrowingShipperLeavesDedupStateIntact(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir, new BrokenHost(false, true));

        f.pipeline().process(error("alpha", f.clock().get()));
        f.pipeline().process(error("alpha", f.clock().get()));

        // the report reached disk, so the repeat is a summary — not a second full report
        assertThat(reportCount(f.file())).isEqualTo(1);
        assertThat(f.pipeline().stats().summariesWritten()).isEqualTo(1);
        assertThat(f.pipeline().stats().reportsWritten()).isEqualTo(1);
    }

    @Test
    void aThrowingSelfLogNeitherParksThePipelineNorStarvesTheShipper(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir, new BrokenHost(true, false));

        f.logDistinctErrors(12);

        ReportPipeline.Stats stats = f.pipeline().stats();
        assertThat(stats.parked()).isFalse();
        assertThat(stats.failures()).isZero();
        assertThat(reportCount(f.file())).isEqualTo(12);
        // the pointer line failing must not skip the emission that follows it
        assertThat(f.host().emitted).hasSize(12);
        assertThat(f.host().warnings).hasSize(1);
        assertThat(f.host().warnings.get(0)).doesNotContain("parked");
    }

    @Test
    void aShipperThrowingAnErrorIsContainedLikeAnyOtherFailure(@TempDir Path dir) throws Exception {
        // a shipper missing a class on the classpath throws an Error, not an Exception
        BrokenHost host = new BrokenHost(false, false) {
            @Override
            public void emitReport(String block) {
                emitAttempts.incrementAndGet();
                throw new NoClassDefFoundError("com/example/ship/LokiClient");
            }
        };
        Fixture f = fixture(dir, host);

        assertThatCode(() -> f.logDistinctErrors(12)).doesNotThrowAnyException();

        ReportPipeline.Stats stats = f.pipeline().stats();
        assertThat(stats.parked()).isFalse();
        assertThat(stats.failures()).isZero();
        assertThat(reportCount(f.file())).isEqualTo(12);
        assertThat(host.emitAttempts).hasValue(12);
        assertThat(host.warnings).hasSize(1);
    }

    @Test
    void aBrokenWarnChannelOnTopOfABrokenShipperStillCostsNothing(@TempDir Path dir) throws Exception {
        BrokenHost host = new BrokenHost(true, true) {
            @Override
            public void warn(String message, Throwable t) {
                super.warn(message, t);
                throw new IllegalStateException("status channel down too");
            }
        };
        Fixture f = fixture(dir, host);

        assertThatCode(() -> f.logDistinctErrors(12)).doesNotThrowAnyException();

        ReportPipeline.Stats stats = f.pipeline().stats();
        assertThat(stats.parked()).isFalse();
        assertThat(stats.failures()).isZero();
        assertThat(reportCount(f.file())).isEqualTo(12);
        // each guard tried its one warning, once, even though both warnings threw
        assertThat(host.warnings).hasSize(2);
    }

    @Test
    void aBrokenSelfLogAndABrokenShipperAreWarnedOnceEachAndNamedApart(@TempDir Path dir) {
        Fixture f = fixture(dir, new BrokenHost(true, true));

        f.logDistinctErrors(12);

        assertThat(f.host().warnings).hasSize(2);
        assertThat(f.host().warnings).filteredOn(w -> w.contains("'" + ReportPipeline.REPORTS_LOGGER + "'"))
                .hasSize(1);
        assertThat(f.host().warnings).filteredOn(w -> w.contains("'" + ReportPipeline.SELF_LOGGER + "'"))
                .hasSize(1);
        assertThat(f.host().warnings).noneMatch(w -> w.contains("parked"));
    }

    @Test
    void aThrowingStartupAnnouncementIsNotAWriteFailure(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir, new BrokenHost(true, false));

        // the announcement fires on the first event of any level, before anything is written
        f.pipeline().process(new LogEventData(f.clock().get(), "INFO", false, "com.example.Logger",
                "main", "hello", new Object[0], "hello", Map.of(), null));

        assertThat(f.pipeline().stats().failures()).isZero();
        assertThat(f.host().warnings).hasSize(1);
        f.logDistinctErrors(1);
        assertThat(reportCount(f.file())).isEqualTo(1);
    }

    @Test
    void underConcurrencyTheShipperWarningIsStillExactlyOne(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir, new BrokenHost(false, true));
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        f.pipeline().process(error("t" + id + "e" + i, f.clock().get()));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ReportPipeline.Stats stats = f.pipeline().stats();
        assertThat(stats.parked()).isFalse();
        assertThat(stats.failures()).isZero();
        assertThat(stats.reportsWritten()).isEqualTo(threads * perThread);
        assertThat(reportCount(f.file())).isEqualTo(threads * perThread);
        assertThat(f.host().emitAttempts).hasValue(threads * perThread);
        assertThat(f.host().warnings).hasSize(1);
    }

}
