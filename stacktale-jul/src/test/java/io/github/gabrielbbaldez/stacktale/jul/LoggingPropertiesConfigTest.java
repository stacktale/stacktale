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
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code logging.properties} path — the one the README documents — driven through a real
 * {@link LogManager#readConfiguration}.
 *
 * <p>Every other test in this module builds {@code Settings} programmatically, so the whole
 * declarative mechanism was unexercised and seven documented keys were silently dropped: a
 * typo'd name and a key nobody wired look identical from outside (#124).
 *
 * <p>Each test asserts the resulting <em>behaviour</em>, not that parsing did not throw. A key
 * that is read and then ignored would pass the second and fail the first.
 */
class LoggingPropertiesConfigTest {

    private StacktaleJulHandler handler;

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) handler.close();
        LogManager.getLogManager().reset();
        LogManager.getLogManager().readConfiguration();
    }

    /** Installs {@code properties} as the JUL configuration and builds the handler from it. */
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

    static class ExposesPii extends RuntimeException {
        ExposesPii(String message) { super(message); }
        public String getCustomerEmail() { return "someone@example.com"; }
    }

    @Test
    void captureExceptionFieldsOffStopsGettersBeingCalled(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        // This one is a privacy control, not a tuning knob: it decides whether getters on the
        // user's own exception types run and land in the report. Unwired, a JUL user whose
        // exceptions expose PII had no way to turn it off.
        StacktaleJulHandler h = configure(
                key("file") + " = " + escape(file) + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n"
                        + key("captureExceptionFields") + " = false\n");

        h.publish(severe("com.acme.Orders", "checkout failed", new ExposesPii("boom")));

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("ExposesPii: boom");
        assertThat(content).doesNotContain("someone@example.com");
        assertThat(content).doesNotContain("customerEmail");
    }

    @Test
    void captureExceptionFieldsDefaultsToOnSoTheKeyIsProvedToDoSomething(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleJulHandler h = configure(
                key("file") + " = " + escape(file) + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n");

        h.publish(severe("com.acme.Orders", "checkout failed", new ExposesPii("boom")));

        // the counterpart of the test above: without it, "no PII in the report" would pass
        // just as well if field capture had never worked at all
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("customerEmail");
    }

    @Test
    void reportErrorsWithoutThrowableOffSkipsThrowablelessErrors(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleJulHandler h = configure(
                key("file") + " = " + escape(file) + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n"
                        + key("reportErrorsWithoutThrowable") + " = false\n");

        h.publish(severe("com.acme.Pay", "payment rejected", null));

        assertThat(Files.exists(file) && Files.size(file) > 0).isFalse();
    }

    @Test
    void truncateOnStartDropsThePreviousRun(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        Files.writeString(file, "# format st/1\nleft over from the previous run\n");

        StacktaleJulHandler h = configure(
                key("file") + " = " + escape(file) + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n"
                        + key("truncateOnStart") + " = true\n");
        h.publish(severe("com.acme.Orders", "fresh", new IllegalStateException("x")));

        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .doesNotContain("left over")
                .contains("fresh");
    }

    @Test
    void containerLoggersSuppressTheContainersReLogOfAFailureWeJustReported(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("errors-ai.log");
        // Not "excluded from the story" — a container logger is one whose re-log of a failure
        // the application already reported, on the same thread and inside the echo window, is
        // a duplicate. Only Tomcat's prefix was built in; JUL could not add to the list.
        StacktaleJulHandler h = configure(
                key("file") + " = " + escape(file) + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n"
                        + key("containerLoggers") + " = org.noisy\n");

        IllegalStateException boom = new IllegalStateException("x");
        h.publish(severe("com.acme.Orders", "checkout failed", boom));
        // the framework catching the same failure and logging it again a moment later
        h.publish(severe("org.noisy.Dispatcher", "Servlet.service() threw exception", boom));

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("checkout failed");
        assertThat(content).doesNotContain("Servlet.service()");
    }

    @Test
    void zoneMovesTheReportTimestamp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleJulHandler h = configure(
                key("file") + " = " + escape(file) + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n"
                        + key("zone") + " = Pacific/Kiritimati\n"); // UTC+14, unlike any CI default

        LogRecord record = severe("com.acme.Orders", "checkout failed", new IllegalStateException("x"));
        // 2024-01-01T00:00:00Z. Not the epoch: Kiritimati only moved to +14 in 1995, and at
        // epoch 0 it sat at −10:40 — which renders as 1969-12-31 and looks like a bug.
        record.setMillis(1_704_067_200_000L);
        h.publish(record);

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("2024-01-01 14:00");
    }

    @Test
    void aBadZoneKeepsTheDefaultAndSaysSoRatherThanSilentlyShiftingEveryTimestamp(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("errors-ai.log");
        StacktaleJulHandler h = configure(
                key("file") + " = " + escape(file) + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n"
                        + key("zone") + " = Middle/Earth\n");

        h.publish(severe("com.acme.Orders", "checkout failed", new IllegalStateException("x")));

        // construction survives; the report is written with the system default zone
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("checkout failed");
    }

    @Test
    void emitReportsToLoggerAlsoSendsTheBlockThroughJul(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        java.util.List<String> throughJul = new java.util.ArrayList<>();
        Logger reports = Logger.getLogger(io.github.gabrielbbaldez.stacktale.ReportPipeline.REPORTS_LOGGER);
        java.util.logging.Handler collector = new java.util.logging.Handler() {
            @Override public void publish(LogRecord r) { throughJul.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };

        StacktaleJulHandler h = configure(
                key("file") + " = " + escape(file) + "\n"
                        + key("appPackages") + " = com.acme\n"
                        + key("installUncaughtHandler") + " = false\n"
                        + key("emitReportsToLogger") + " = true\n");
        reports.addHandler(collector);
        try {
            h.publish(severe("com.acme.Orders", "checkout failed", new IllegalStateException("x")));
        } finally {
            reports.removeHandler(collector);
        }

        assertThat(throughJul).anyMatch(m -> m.contains("━━━ ERROR #"));
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("checkout failed");
    }

    @Test
    void installUncaughtHandlerIsReadFromThePropertiesFile(@TempDir Path dir) throws Exception {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        try {
            configure(key("file") + " = " + escape(dir.resolve("errors-ai.log")) + "\n"
                    + key("appPackages") + " = com.acme\n"
                    + key("installUncaughtHandler") + " = false\n");

            // the key is in the README's JUL list; nothing proved it was honoured
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(before);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }

    /** JUL properties are Properties-format, where a Windows path's backslashes are escapes. */
    private static String escape(Path path) {
        return path.toString().replace("\\", "/");
    }
}
