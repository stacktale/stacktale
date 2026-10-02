package io.github.gabrielbbaldez.stacktale.jul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code containerLoggers} adds to Tomcat's prefix on every backend, as the README says
 * ("Extra logger prefixes ... All three default to Tomcat's"). Logback, Log4j2, the Spring
 * starter and Quarkus all merge; the JUL handler replaced the list, so naming one extra
 * prefix silently turned off Tomcat echo suppression, and each failure Tomcat re-logs was
 * counted twice (a "repeated 2×" line, a doubled "seen N×").
 */
class JulContainerLoggersTest {

    private StacktaleJulHandler handler;

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) handler.close();
        LogManager.getLogManager().reset();
        LogManager.getLogManager().readConfiguration();
    }

    private StacktaleJulHandler configure(String properties) throws Exception {
        LogManager.getLogManager().readConfiguration(
                new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)));
        handler = new StacktaleJulHandler();
        return handler;
    }

    private static String key(String name) {
        return StacktaleJulHandler.class.getName() + "." + name;
    }

    private static LogRecord severe(String logger, String message, Throwable thrown) {
        LogRecord record = new LogRecord(Level.SEVERE, message);
        record.setLoggerName(logger);
        record.setThrown(thrown);
        return record;
    }

    /** The app's report, then Tomcat's StandardWrapperValve re-logging it through JULI. */
    private static String reportAndEcho(StacktaleJulHandler h, Path file) throws Exception {
        IllegalStateException boom = new IllegalStateException("x");
        h.publish(severe("com.acme.Orders", "checkout failed", boom));
        h.publish(severe("org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/].[dispatcherServlet]",
                "Servlet.service() for servlet [dispatcherServlet] threw exception", boom));
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String base(Path file) {
        return key("file") + " = " + file.toString().replace("\\", "/") + "\n"
                + key("appPackages") + " = com.acme\n"
                + key("installUncaughtHandler") + " = false\n";
    }

    @Test
    void byDefaultTomcatsEchoIsNotCountedAsARepeat(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        // the echo carries the same throwable, so had it been processed it would show up as a
        // "repeated 2×" line rather than a second report
        String content = reportAndEcho(configure(base(file)), file);

        assertThat(content).contains("checkout failed");
        assertThat(content).doesNotContain(" repeated 2×");
    }

    @Test
    void anExtraContainerLoggerKeepsTomcatsEchoSuppressed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        String content = reportAndEcho(
                configure(base(file) + key("containerLoggers") + " = org.noisy\n"), file);

        assertThat(content).contains("checkout failed");
        assertThat(content).doesNotContain(" repeated 2×");
    }

    @Test
    void theExtraPrefixItselfIsStillSuppressed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleJulHandler h = configure(base(file) + key("containerLoggers") + " = org.noisy\n");

        IllegalStateException boom = new IllegalStateException("x");
        h.publish(severe("com.acme.Orders", "checkout failed", boom));
        h.publish(severe("org.noisy.Dispatcher", "Servlet.service() threw exception", boom));

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain(" repeated 2×");
    }
}
