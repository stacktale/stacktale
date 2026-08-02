package io.github.gabrielbbaldez.stacktale.junit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * The reason this module hooks into {@code ActivePipeline} instead of building its own.
 *
 * <p>A test failure on its own is just an exception. What makes the report worth pasting to
 * an assistant is the story — what the code logged on the way to failing. Those events live
 * in the running adapter's story buffer, so the listener has to report through that same
 * pipeline; a private one would produce a report with an empty story.
 */
class StoryFromRunningAppenderTest {

    private LoggerContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop(); // also unregisters the pipeline
        Holder.log = null;
    }

    @Test
    void theTestFailureReportCarriesWhatTheCodeLoggedFirst(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file);
        Holder.log = ctx.getLogger("com.acme.CheckoutService");

        runInOwnLauncher(LoggingSample.class);

        ctx.stop(); // flush
        String written = Files.readString(file);

        // the failure became a report at all
        assertThat(written).contains("IllegalStateException: gateway refused");
        assertThat(written).contains("test.method=confirmsAnOrder");
        // ...and it carries the lines logged before the throw, which is the whole point:
        // both went through the adapter's pipeline, so they share one story buffer
        assertThat(written).contains("story (thread ");
        assertThat(written).contains("confirming order 889");
        assertThat(written).contains("gateway timeout, retrying once");
    }

    /**
     * A known limitation, pinned so it cannot regress silently.
     *
     * <p>{@code StoryBuffer.record} files an event under its correlation key <em>or</em>
     * under its thread, never both. A listener is notified after the test method has
     * returned, so the MDC is already gone and the failure event carries no correlation
     * key — it looks in the thread bucket and finds nothing, while the lines the test
     * logged went to the traceId bucket. Capturing the MDC while the test is still running
     * needs a Jupiter extension rather than a launcher listener.
     */
    @Test
    void aCorrelationKeySetByTheTestStrandsTheStoryWithoutTheExtension(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file);
        Holder.log = ctx.getLogger("com.acme.CheckoutService");

        runInOwnLauncher(CorrelatedSample.class);

        ctx.stop();
        String written = Files.readString(file);

        // The listener alone still behaves exactly as it did: it is notified after the test
        // method returned, so the MDC is unwound, the failure event carries no traceId, and it
        // reads the thread bucket while the log line went to the traceId one. Kept as a test
        // rather than deleted — the zero-config path is the headline feature and this is the
        // shape of its one limitation.
        assertThat(written).contains("IllegalStateException: gateway refused");
        assertThat(written).doesNotContain("confirming order 889");
    }

    @Test
    void theExtensionGivesTheCorrelatedTestItsStoryBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        startAppender(file);
        Holder.log = ctx.getLogger("com.acme.CheckoutService");

        runInOwnLauncher(ExtendedCorrelatedSample.class);

        ctx.stop();
        String written = Files.readString(file);

        assertThat(written).contains("IllegalStateException: gateway refused");
        // the whole point: the lead-up, not just the failure repeated back
        assertThat(written).contains("confirming order 889");
        assertThat(written).contains("traceId=9f3a");
    }

    @Test
    void oneFileIsUsedWhenAnAdapterIsAlreadyRunning(@TempDir Path dir) throws Exception {
        Path appenderFile = dir.resolve("errors-ai.log");
        Path unused = dir.resolve("junit-only.log");
        startAppender(appenderFile);
        Holder.log = ctx.getLogger("com.acme.CheckoutService");

        // even asked for its own file, the listener must defer to the running adapter
        System.setProperty(StacktaleTestListener.FILE_PROPERTY, unused.toString());
        try {
            runInOwnLauncher(LoggingSample.class);
        } finally {
            System.clearProperty(StacktaleTestListener.FILE_PROPERTY);
        }

        ctx.stop();
        assertThat(Files.readString(appenderFile)).contains("gateway refused");
        assertThat(Files.exists(unused)).isFalse();
    }

    private void startAppender(Path file) {
        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setAppPackages("com.acme,io.github.gabrielbbaldez.stacktale.junit");
        appender.setInstallUncaughtHandler(false);
        appender.start();
        ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
    }

    private void runInOwnLauncher(Class<?> sample) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(sample))
                .build();
        Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
                .enableTestExecutionListenerAutoRegistration(false)
                .build());
        launcher.execute(request, new StacktaleTestListener());
    }

    /** Lets the sample log into the test's hand-built context. */
    static class Holder {
        static Logger log;
    }

    /** Stands in for code under test that logs its way to a failure. */
    static class LoggingSample {
        @Test
        void confirmsAnOrder() {
            Holder.log.info("confirming order {}", 889);
            Holder.log.warn("gateway timeout, retrying once");
            throw new IllegalStateException("gateway refused");
        }
    }

    /**
     * The same, but with a correlation key in the MDC, and with the extension registered so
     * the key survives long enough for the listener to use it.
     */
    @org.junit.jupiter.api.extension.ExtendWith(StacktaleExtension.class)
    static class ExtendedCorrelatedSample {
        // Teardown, the way a filter, a request scope or a Spring test fixture unwinds it —
        // @AfterEach runs after afterTestExecution, so the key is still there to capture.
        // A test that clears its own MDC in a finally inside the method body is out of reach
        // for any hook: that block runs before the exception even leaves the method.
        @org.junit.jupiter.api.AfterEach
        void clearContext() {
            MDC.remove("traceId");
        }

        @Test
        void confirmsAnOrder() {
            MDC.put("traceId", "9f3a");
            Holder.log.info("confirming order {}", 889);
            throw new IllegalStateException("gateway refused");
        }
    }

    /** The same, but with a correlation key in the MDC — see the limitation above. */
    static class CorrelatedSample {
        @Test
        void confirmsAnOrder() {
            MDC.put("traceId", "9f3a");
            try {
                Holder.log.info("confirming order {}", 889);
                throw new IllegalStateException("gateway refused");
            } finally {
                MDC.remove("traceId");
            }
        }
    }
}
