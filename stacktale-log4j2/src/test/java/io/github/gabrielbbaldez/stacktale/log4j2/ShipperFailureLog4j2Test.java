package io.github.gabrielbbaldez.stacktale.log4j2;

import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log4j2's {@code ignoreExceptions="false"} is a documented way to make an appender's failure
 * reach the caller — which, for {@code stacktale.reports}, is stacktale's own emission. That
 * throw used to count as a failed write, and five of them parked the pipeline for the rest of
 * the run while the report file was taking every block.
 */
class ShipperFailureLog4j2Test {

    private LoggerContext ctx;
    private StatusListener listener;

    @AfterEach
    void tearDown() {
        if (listener != null) StatusLogger.getLogger().removeListener(listener);
        if (ctx != null) ctx.stop();
    }

    /** A shipper that is down, configured to let its failures reach the logging call. */
    private static final class DeadShipper extends AbstractAppender {
        final AtomicInteger calls = new AtomicInteger();

        DeadShipper() {
            super("DEAD_SHIPPER", null, null, false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            calls.incrementAndGet();
            throw new IllegalStateException("shipper down");
        }
    }

    private static RuntimeException distinctError(int i) {
        RuntimeException e = new IllegalStateException("boom " + i);
        e.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.Service" + i, "call", "Service" + i + ".java", 10 + i)
        });
        return e;
    }

    @Test
    void anAppenderThatThrowsOnTheReportsLoggerDoesNotParkStacktale(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        String xml = """
                <Configuration status="WARN" packages="io.github.gabrielbbaldez.stacktale.log4j2">
                  <Appenders>
                    <Stacktale name="STACKTALE" file="%s" appPackages="com.acme"
                               installUncaughtHandler="false" emitReportsToLogger="true"/>
                  </Appenders>
                  <Loggers>
                    <Root level="info">
                      <AppenderRef ref="STACKTALE"/>
                    </Root>
                  </Loggers>
                </Configuration>
                """.formatted(file.toString().replace("\\", "/"));
        ctx = Configurator.initialize(null, new ConfigurationSource(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));

        DeadShipper shipper = new DeadShipper();
        shipper.start();
        Configuration config = ctx.getConfiguration();
        LoggerConfig reports = new LoggerConfig(ReportPipeline.REPORTS_LOGGER, Level.INFO, false);
        reports.addAppender(shipper, Level.INFO, null);
        config.addLogger(ReportPipeline.REPORTS_LOGGER, reports);
        ctx.updateLoggers();

        List<String> warnings = new CopyOnWriteArrayList<>();
        listener = new StatusListener() {
            @Override public void log(StatusData data) {
                if (data.getMessage().getFormattedMessage().startsWith("stacktale:")) {
                    warnings.add(data.getMessage().getFormattedMessage());
                }
            }
            @Override public Level getStatusLevel() { return Level.WARN; }
            @Override public void close() { }
        };
        StatusLogger.getLogger().registerListener(listener);

        Logger log = ctx.getLogger("com.acme.Checkout");
        for (int i = 0; i < 8; i++) {
            log.error("call {} failed", i, distinctError(i));
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content.lines().filter(l -> l.startsWith("━━━ ERROR #"))).hasSize(8);
        assertThat(content).contains("boom 7"); // the last one, after the old parking point
        // proves the shipper really sat on the path the pipeline emits through
        assertThat(shipper.calls.get()).isEqualTo(8);
        StacktaleAppender stacktale = ctx.getConfiguration().getAppender("STACKTALE");
        assertThat(stacktale.stats().parked()).isFalse();
        assertThat(stacktale.stats().failures()).isZero();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains(ReportPipeline.REPORTS_LOGGER).doesNotContain("parked");
    }
}
