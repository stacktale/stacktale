package io.github.gabrielbbaldez.stacktale.mcp;

import io.github.gabrielbbaldez.stacktale.LogEventData;
import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test that spans both ends of {@code st-json/1}.
 *
 * <p>The format has one writer — {@code JsonReportRenderer} in {@code stacktale-core} — and one
 * reader, {@link StReportFile}, which picks its fields out by hand: {@code node.path("ts")},
 * {@code node.path("error")}. Until #248 there was no build edge between the two modules, so the
 * test covering the JSON path wrote the format out by hand. Rename a field in the renderer,
 * update the renderer's own test the way a deliberate format change would, and this module kept
 * passing on its stale fixture while the server answered with an empty headline —
 * {@code path(...)} on a field that is gone returns a missing node rather than failing.
 *
 * <p>So the report here is not written by the test. It comes out of a real {@link ReportPipeline}
 * in JSON mode, which is what users run, and is read back through the class the server uses.
 *
 * <p>One field is not covered and cannot be from here: the {@code repro} block needs
 * {@code stacktale-agent} attached as a {@code -javaagent} to capture arguments at the throw
 * site, so a pipeline on its own never emits one. That half still rides on the hand-written
 * fixture in {@code StacktaleMcpServerTest}.
 */
class StJsonRoundTripTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void whatTheRendererWritesIsWhatTheReaderReads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        writeReports(file, 1);

        List<StReportFile.StReport> reports = new StReportFile(file).read();

        assertThat(reports)
                .withFailMessage("the reader found nothing in a file the renderer just wrote")
                .hasSize(1);
        StReportFile.StReport report = reports.get(0);

        // Each of these is a separate hand-written path(...) on the reader's side, so each is its
        // own way for a rename to go unnoticed.
        assertThat(report.id()).as("id").isNotBlank();
        assertThat(report.timestamp()).as("timestamp")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
        assertThat(report.headline()).as("headline")
                .contains("IllegalStateException")
                .contains("payment gateway refused");
        assertThat(report.repeats()).as("repeats").isEqualTo(1);
        assertThat(report.block()).as("block").contains("PaymentService");
    }

    /**
     * The repeat count arrives as a separate entry in the file rather than inside the report, so
     * it travels a different path through the reader than the fields above.
     */
    @Test
    void aRecurrenceCountSurvivesTheRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        writeReports(file, 3);

        List<StReportFile.StReport> reports = new StReportFile(file).read();

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).repeats())
                .withFailMessage("three occurrences of one error should read back as a count above 1")
                .isGreaterThan(1);
    }

    /** Logs the same error {@code times} times through a real JSON-mode pipeline. */
    private void writeReports(Path file, int times) {
        ReportPipeline pipeline = ReportPipeline.create(
                ReportPipeline.Settings.builder()
                        .file(file.toString())
                        .appPackages(List.of("com.acme"))
                        .jsonFormat(true)
                        .build(),
                new ReportPipeline.Host() {
                    @Override
                    public void selfLog(String message) {
                    }

                    @Override
                    public void warn(String message, Throwable t) {
                        warnings.add(message);
                    }
                });
        assertThat(pipeline.isActive())
                .withFailMessage("the pipeline could not open %s, so nothing here measures anything", file)
                .isTrue();

        RuntimeException cause = new IllegalStateException("payment gateway refused");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.PaymentService", "charge", "PaymentService.java", 118),
        });
        for (int i = 0; i < times; i++) {
            pipeline.process(new LogEventData(1_000_412L + i, "ERROR", true, "com.acme.PaymentService",
                    "http-1", "charge failed for order {}", new Object[]{889},
                    "charge failed for order 889", Map.of(), cause));
        }
        pipeline.close();

        // The pipeline swallows its own failures by design, so a warning is the only trace that
        // the file below is not what the test thinks it is.
        assertThat(warnings).withFailMessage("the pipeline warned while writing: %s", warnings).isEmpty();
    }
}
