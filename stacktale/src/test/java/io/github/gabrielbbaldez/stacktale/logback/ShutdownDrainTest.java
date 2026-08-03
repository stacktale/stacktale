package io.github.gabrielbbaldez.stacktale.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trailing repeat counters on plain Logback (#127).
 *
 * <p>{@code close()} drains them and runs from {@code stop()}, which Logback calls only on
 * {@code LoggerContext.stop()} — and Logback registers no shutdown hook unless
 * {@code <shutdownHook/>} is configured. For the quickstart this project documents, that drain
 * never happened, and the file ended with whatever the last burst flush wrote. The count was
 * not missing but <em>stale</em>, which is worse: fifty identical errors left {@code repeated
 * 2×} as the final word and a reader believes it.
 *
 * <p>The JVM-exit half is exercised in a forked process, because a shutdown hook cannot be
 * observed from inside the JVM that is still running.
 */
class ShutdownDrainTest {

    private static final Pattern REPEATED = Pattern.compile("repeated (\\d+)×");

    /** The highest {@code repeated N×} in the file — the count a reader would end up believing. */
    private static int finalRepeatCount(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = REPEATED.matcher(content);
        int last = 0;
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private static LoggerContext configure(Path file) throws Exception {
        String xml = """
                <configuration>
                  <appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender">
                    <file>%s</file>
                    <appPackages>com.acme</appPackages>
                    <installUncaughtHandler>false</installUncaughtHandler>
                  </appender>
                  <root level="info"><appender-ref ref="STACKTALE"/></root>
                </configuration>
                """.formatted(file.toString().replace("\\", "/"));
        LoggerContext ctx = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(ctx);
        configurator.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return ctx;
    }

    private static StacktaleAppender appenderOf(LoggerContext ctx) {
        return (StacktaleAppender) ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
                .getAppender("STACKTALE");
    }

    @Test
    void startRegistersTheDrainAndStopTakesItBackOff(@TempDir Path dir) throws Exception {
        LoggerContext ctx = configure(dir.resolve("errors-ai.log"));
        StacktaleAppender appender = appenderOf(ctx);

        Thread hook = appender.shutdownDrain;
        assertThat(hook).as("start() registers a drain").isNotNull();
        // it really is with the JVM: removing it succeeds exactly once
        assertThat(Runtime.getRuntime().removeShutdownHook(hook)).isTrue();
        Runtime.getRuntime().addShutdownHook(hook); // put it back for stop() to remove

        ctx.stop();

        assertThat(appender.shutdownDrain).as("stop() clears the reference").isNull();
        // A hook left behind pins the stopped context, which is the leak UncaughtHandler had.
        // false here means stop() had already taken it off the JVM's list.
        assertThat(Runtime.getRuntime().removeShutdownHook(hook)).isFalse();
    }

    @Test
    void aContextStopStillDrainsWithoutWaitingForTheJvm(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        LoggerContext ctx = configure(file);
        org.slf4j.Logger log = ctx.getLogger("com.acme.OrderService");
        IllegalStateException boom = new IllegalStateException("gateway refused");
        for (int i = 0; i < 50; i++) log.error("checkout failed for order {}", 42, boom);

        int beforeStop = finalRepeatCount(file);
        ctx.stop();

        assertThat(finalRepeatCount(file))
                .as("the drain writes the true total, not the last burst flush")
                .isEqualTo(50)
                .isGreaterThan(beforeStop);
    }

    @Test
    void aJvmExitWithoutStoppingTheContextStillLeavesTheTrueCount(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        Path source = dir.resolve("ExitsWithoutStopping.java");
        // Deliberately never touches LoggerContext.stop() and configures no <shutdownHook/> —
        // exactly the shape of the README's plain-Logback quickstart.
        Files.writeString(source, """
                import org.slf4j.LoggerFactory;
                public class ExitsWithoutStopping {
                    public static void main(String[] a) {
                        var log = LoggerFactory.getLogger("com.acme.OrderService");
                        var boom = new IllegalStateException("gateway refused");
                        for (int i = 0; i < 50; i++) log.error("checkout failed for order {}", 42, boom);
                    }
                }
                """);
        Path config = dir.resolve("logback-test-exit.xml");
        Files.writeString(config, """
                <configuration>
                  <appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender">
                    <file>%s</file>
                    <appPackages>com.acme</appPackages>
                    <installUncaughtHandler>false</installUncaughtHandler>
                  </appender>
                  <root level="info"><appender-ref ref="STACKTALE"/></root>
                </configuration>
                """.formatted(file.toString().replace("\\", "/")));

        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath,
                "-Dlogback.configurationFile=" + config,
                source.toString()) // single-file source mode: no separate compile step
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("the forked JVM exited cleanly:%n%s", output).isZero();

        // Without the hook this was 2 — the last burst flush, with 48 occurrences unaccounted
        // for and nothing saying so.
        assertThat(finalRepeatCount(file)).isEqualTo(50);
    }
}
