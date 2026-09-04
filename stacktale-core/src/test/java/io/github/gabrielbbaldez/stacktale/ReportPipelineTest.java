package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rollback/confirm protocol from #51/#57, driven directly rather than through a logging
 * backend.
 *
 * <p>Why this class exists (#126): the protocol's failure mode is *silence*. When a report is
 * decided but never written, {@link Deduper} must be told to unwind the decision — otherwise
 * {@code reportPending} stays true and {@code decide()} answers {@code SILENT} for that
 * fingerprint until the dedup window rolls over. An error simply stops being reported for
 * minutes, and nothing goes red. Every test here asserts on what reached the file, because
 * that is the only thing a user of stacktale can observe.
 */
class ReportPipelineTest {

    /**
     * Renderer that can be armed to fail on the next {@code stormLine}, standing in for a
     * write that does not reach the file. The pipeline treats a throw from the render and a
     * throw from the append identically — both leave {@code storm.action() == STORM_LINE}
     * without the line on disk — and this is the one that needs no unwritable filesystem.
     */
    private static final class BreakableRenderer implements Renderer {
        private final Renderer delegate;
        private boolean failStormLine;

        BreakableRenderer(Renderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public String render(Report report) {
            return delegate.render(report);
        }

        @Override
        public String renderSummary(String id, int count, long lastMillis) {
            return delegate.renderSummary(id, count, lastMillis);
        }

        @Override
        public String fileHeader() {
            return delegate.fileHeader();
        }

        @Override
        public String sessionMarker(long epochMillis, long pid) {
            return delegate.sessionMarker(epochMillis, pid);
        }

        @Override
        public String stormLine(int suppressed, int limit) {
            if (failStormLine) {
                failStormLine = false; // one-shot: the run after this one must be able to recover
                throw new UncheckedIOException(new java.io.IOException("disk full"));
            }
            return delegate.stormLine(suppressed, limit);
        }
    }

    /** Collects what the pipeline told its host, so a swallowed failure is still visible. */
    private static final class RecordingHost implements ReportPipeline.Host {
        final List<String> warnings = new ArrayList<>();
        final List<String> selfLogs = new ArrayList<>();
        final List<String> emitted = new ArrayList<>();

        @Override
        public void selfLog(String message) {
            selfLogs.add(message);
        }

        @Override
        public void warn(String message, Throwable t) {
            warnings.add(message);
        }

        @Override
        public void emitReport(String block) {
            emitted.add(block);
        }
    }

    /** A pipeline writing to {@code dir/errors-ai.log}, on a clock the test advances by hand. */
    private record Fixture(ReportPipeline pipeline,
                           BreakableRenderer renderer,
                           RecordingHost host,
                           AtomicLong clock,
                           Path file) {

        String contents() {
            try {
                return Files.exists(file) ? Files.readString(file) : "";
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Lines that *begin* with {@code prefix}.
         *
         * <p>Anchored to the line start on purpose: the file header documents its own
         * delimiters, so it contains the literal strings {@code ━━━ ERROR #<id> ━━━},
         * {@code ━ #<id> repeated N× ━} and {@code ━ storm: …} inside {@code #} comments. A
         * plain substring search counts the documentation as an occurrence.
         */
        int linesStartingWith(String prefix) {
            int n = 0;
            for (String line : contents().split("\n", -1)) {
                if (line.startsWith(prefix)) n++;
            }
            return n;
        }

        /** How many full st/1 report blocks reached the file. */
        int reportCount() {
            return linesStartingWith("━━━ ERROR #");
        }

        /** How many "same error repeated N×" lines reached the file. */
        int summaryCount() {
            return linesStartingWith("━ #");
        }

        /** How many storm (rate-limit) lines reached the file. */
        int stormLineCount() {
            return linesStartingWith("━ storm:");
        }
    }

    private static Fixture fixture(Path dir, int maxReportsPerMinute) {
        Path file = dir.resolve("errors-ai.log");
        ReportPipeline.Settings settings = ReportPipeline.Settings.builder()
                .file(file.toString())
                .appName("test")
                .appVersion("0")
                // The container-echo guard reads System.currentTimeMillis() directly, not the
                // injected clock; off, so it cannot swallow an event the test just fed in.
                .echoSuppressionMillis(0)
                .maxReportsPerMinute(maxReportsPerMinute)
                .zone(ZoneId.of("UTC"))
                .build();

        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        Renderer real = new ReportRenderer(settings.zone(), Redactor.disabled());
        BreakableRenderer renderer = new BreakableRenderer(real);
        RecordingHost host = new RecordingHost();
        ReportWriter writer = new ReportWriter(file, settings.maxFileBytes(), real.fileHeader(),
                null, false, settings.maxBackups(), host::warn);

        return new Fixture(
                ReportPipeline.forTesting(settings, host, writer, renderer, clock::get),
                renderer, host, clock, file);
    }

    /** An error event whose fingerprint is stable for a given {@code marker}. */
    private static LogEventData error(String marker, long epochMillis) {
        Throwable t = new IllegalStateException(marker);
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example." + marker, "run", marker + ".java", 42)
        });
        return new LogEventData(epochMillis, "ERROR", true, "com.example.Logger", "main",
                "failed: " + marker, new Object[0], "failed: " + marker, Map.of(), t);
    }

    @Test
    void aWrittenReportIsConfirmedAndTheRepeatBecomesASummary(@TempDir Path dir) {
        Fixture f = fixture(dir, 0); // storm control off

        f.pipeline().process(error("alpha", f.clock().get()));
        f.pipeline().process(error("alpha", f.clock().get()));

        // one report, then the second occurrence is a summary rather than a second report:
        // confirmReport cleared reportPending, so decide() could answer SUMMARY
        assertThat(f.reportCount()).isEqualTo(1);
        assertThat(f.summaryCount()).isEqualTo(1);
        assertThat(f.host().warnings).isEmpty();
    }

    @Test
    void aStormSuppressedReportIsRolledBackSoTheWindowStillReportsIt(@TempDir Path dir) {
        Fixture f = fixture(dir, 1);

        f.pipeline().process(error("alpha", f.clock().get()));   // ALLOW -> written
        f.pipeline().process(error("beta", f.clock().get()));    // over limit -> STORM_LINE
        f.pipeline().process(error("gamma", f.clock().get()));   // over limit -> SUPPRESS

        assertThat(f.reportCount()).isEqualTo(1);
        assertThat(f.stormLineCount()).isEqualTo(1);

        // Past the storm window, gamma is not stuck: SUPPRESS rolled its decision back, so a
        // fresh REPORT is still available to it.
        f.clock().addAndGet(61_000);
        f.pipeline().process(error("gamma", f.clock().get()));

        assertThat(f.reportCount()).isEqualTo(2);
        assertThat(f.contents()).contains("IllegalStateException: gamma");
    }

    /**
     * The gap #126 names: a storm-line write that throws used to skip both
     * {@code confirmStormLine} and {@code rollback}, leaving the error's fingerprint pending
     * and therefore SILENT until the dedup window rolled over.
     */
    @Test
    void aFailedStormLineWriteStillLetsTheNextOccurrenceReport(@TempDir Path dir) {
        Fixture f = fixture(dir, 1);

        f.pipeline().process(error("alpha", f.clock().get())); // ALLOW -> written
        assertThat(f.reportCount()).isEqualTo(1);

        f.renderer().failStormLine = true;
        f.pipeline().process(error("beta", f.clock().get()));  // STORM_LINE, and the write fails

        // never throws out of process(), but the host is told once
        assertThat(f.stormLineCount()).isZero();
        assertThat(f.host().warnings).isNotEmpty();

        // Past the storm window beta is allowed again. Before the fix, beta's fingerprint was
        // still reportPending, decide() answered SILENT, and this stayed at 1 report -- the
        // ~5-minute blind spot.
        f.clock().addAndGet(61_000);
        f.pipeline().process(error("beta", f.clock().get()));

        assertThat(f.reportCount()).isEqualTo(2);
        assertThat(f.contents()).contains("IllegalStateException: beta");
    }

    /** The suppressed count survives a failed storm line: #57 says clear it only once written. */
    @Test
    void aFailedStormLineKeepsItsSuppressedCountForTheNextLine(@TempDir Path dir) {
        Fixture f = fixture(dir, 1);

        f.pipeline().process(error("alpha", f.clock().get()));
        f.renderer().failStormLine = true;
        f.pipeline().process(error("beta", f.clock().get()));   // storm line lost

        // Past the storm-line throttle, the next one carries both suppressions -- the lost
        // line's and its own -- rather than having silently dropped the first.
        f.clock().addAndGet(11_000);
        f.pipeline().process(error("gamma", f.clock().get()));

        assertThat(f.stormLineCount()).isEqualTo(1);
        assertThat(f.contents()).contains("storm: 2 report(s) suppressed");
    }

    @Test
    void closeDrainsThePendingSummaryCountsAndSuppressions(@TempDir Path dir) {
        Fixture f = fixture(dir, 0);

        f.pipeline().process(error("alpha", f.clock().get()));
        f.pipeline().process(error("alpha", f.clock().get())); // count 2, written as a summary
        f.pipeline().process(error("alpha", f.clock().get())); // count 3, throttled -> SILENT

        int summariesBeforeClose = f.summaryCount();
        f.pipeline().close();

        // close() flushes the count that no summary line has reflected yet, so the last
        // occurrences are not lost on shutdown
        assertThat(f.summaryCount()).isGreaterThan(summariesBeforeClose);
    }

    /**
     * The counters have to agree with the file, or they are a second story about the same run.
     * Asserted side by side with the block counts for exactly that reason.
     */
    @Test
    void statsCountWhatReachedTheFileAndWhatDidNot(@TempDir Path dir) {
        Fixture f = fixture(dir, 1); // one full report per window

        f.pipeline().process(error("alpha", f.clock().get()));  // ALLOW -> written
        f.pipeline().process(error("alpha", f.clock().get()));  // repeat -> summary
        f.pipeline().process(error("beta", f.clock().get()));   // over limit -> STORM_LINE
        f.pipeline().process(error("gamma", f.clock().get()));  // over limit -> SUPPRESS

        ReportPipeline.Stats stats = f.pipeline().stats();

        assertThat(stats.active()).isTrue();
        assertThat(stats.parked()).isFalse();
        assertThat(stats.reportsWritten()).isEqualTo(f.reportCount());
        assertThat(stats.summariesWritten()).isEqualTo(f.summaryCount());
        // two errors happened and produced no report of their own — the number a report file
        // cannot tell you, and the reason these counters exist
        assertThat(stats.stormSuppressed()).isEqualTo(2);
        assertThat(stats.failures()).isZero();
    }

    /**
     * The state worth alarming on. A parked pipeline has stopped reporting for the rest of the
     * run, and the only other trace is one warning at the moment it happened.
     */
    @Test
    void statsReportParkedOnceThePipelineHasGivenUp(@TempDir Path dir) {
        Fixture f = fixture(dir, 0);

        f.pipeline().process(error("alpha", f.clock().get()));
        assertThat(f.pipeline().stats().parked()).isFalse();

        try {
            Files.delete(f.file());
            Files.createDirectory(f.file()); // every append from here fails
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        for (int i = 0; i < 6; i++) {
            f.pipeline().process(error("failure" + i, f.clock().get()));
        }

        ReportPipeline.Stats stats = f.pipeline().stats();
        assertThat(stats.parked()).isTrue();
        assertThat(stats.failures()).isGreaterThanOrEqualTo(5);
        assertThat(stats.reportsWritten()).isEqualTo(1); // only the one from before the breakage
    }

    @Test
    void processNeverThrowsAndParksAfterRepeatedWriteFailures(@TempDir Path dir) {
        Fixture f = fixture(dir, 0);
        Path file = f.file();

        f.pipeline().process(error("alpha", f.clock().get()));
        assertThat(f.reportCount()).isEqualTo(1);

        // Replace the report file with a directory: every subsequent append fails, and none of
        // it may escape process() into the application.
        String written = f.contents();
        try {
            Files.delete(file);
            Files.createDirectory(file);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }

        for (int i = 0; i < 6; i++) {
            f.pipeline().process(error("failure" + i, f.clock().get()));
        }

        assertThat(f.host().warnings).isNotEmpty();
        assertThat(f.host().warnings.stream().anyMatch(w -> w.contains("parked"))).isTrue();
        assertThat(written).isNotEmpty(); // the pre-failure report was real
    }
}
