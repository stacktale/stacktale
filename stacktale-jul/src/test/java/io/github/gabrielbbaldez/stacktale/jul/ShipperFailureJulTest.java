package io.github.gabrielbbaldez.stacktale.jul;

import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUL is where a broken shipper bites for real: {@code Logger.log} calls each
 * {@code Handler.publish} directly, so a handler on {@code stacktale.reports} that throws
 * throws straight into stacktale's emission. Five of those used to park the pipeline for the
 * rest of the run while every report was still reaching the file.
 */
class ShipperFailureJulTest {

    private StacktaleJulHandler handler;
    private final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        cleanup.forEach(Runnable::run);
        if (handler != null) handler.close();
        LogManager.getLogManager().reset();
        LogManager.getLogManager().readConfiguration();
    }

    private static String key(String name) {
        return StacktaleJulHandler.class.getName() + "." + name;
    }

    private void attach(String loggerName, Handler h) {
        Logger logger = Logger.getLogger(loggerName);
        logger.addHandler(h);
        cleanup.add(() -> logger.removeHandler(h));
    }

    /** A SEVERE record whose culprit frame is unique to {@code i}: one fingerprint each. */
    private static LogRecord distinctError(int i) {
        RuntimeException e = new IllegalStateException("boom " + i);
        e.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.Service" + i, "call", "Service" + i + ".java", 10 + i)
        });
        LogRecord record = new LogRecord(Level.SEVERE, "call " + i + " failed");
        record.setLoggerName("com.acme.Service" + i);
        record.setThrown(e);
        return record;
    }

    @Test
    void aThrowingHandlerOnTheReportsLoggerDoesNotParkStacktale(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        LogManager.getLogManager().readConfiguration(new ByteArrayInputStream((
                key("file") + " = " + file.toString().replace("\\", "/") + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n"
                        + key("emitReportsToLogger") + " = true\n").getBytes(StandardCharsets.UTF_8)));
        handler = new StacktaleJulHandler();

        int[] shipperCalls = {0};
        attach(ReportPipeline.REPORTS_LOGGER, new Handler() {
            @Override public void publish(LogRecord r) {
                shipperCalls[0]++;
                throw new IllegalStateException("shipper down");
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        List<String> warnings = new ArrayList<>();
        attach(ReportPipeline.SELF_LOGGER, new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        });

        for (int i = 0; i < 8; i++) {
            handler.publish(distinctError(i));
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content.lines().filter(l -> l.startsWith("━━━ ERROR #"))).hasSize(8);
        assertThat(content).contains("boom 7"); // the last one, after the old parking point
        ReportPipeline.Stats stats = handler.stats();
        assertThat(stats.parked()).isFalse();
        assertThat(stats.failures()).isZero();
        assertThat(shipperCalls[0]).isEqualTo(8);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains(ReportPipeline.REPORTS_LOGGER).doesNotContain("parked");
    }
}
