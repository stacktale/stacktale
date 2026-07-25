package io.github.gabrielbbaldez.stacktale;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins stacktale's behavior on Java 21 virtual threads (library baseline is 17, so the
 * vthread executor is reached reflectively and the tests only run on 21+):
 * with MDC correlation the story survives ephemeral threads; without it, each vthread is
 * born empty — use MDC/trace ids, which vthread-era frameworks set anyway.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
class VirtualThreadsTest {

    private LoggerContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop();
        MDC.clear();
    }

    private Path start(Path dir) {
        Path file = dir.resolve("errors-ai.log");
        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setInstallUncaughtHandler(false);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        return file;
    }

    private static ExecutorService virtualExecutor() throws Exception {
        Method m = java.util.concurrent.Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
        return (ExecutorService) m.invoke(null);
    }

    @Test
    void storySurvivesVirtualThreadsWhenMdcIsPropagated(@TempDir Path dir) throws Exception {
        Path file = start(dir);
        Logger log = ctx.getLogger("com.acme.VApp");

        MDC.put("traceId", "vt1");
        log.info("request accepted on the carrier thread");
        ExecutorService vt = virtualExecutor();
        try {
            vt.submit(StacktaleExecutors.wrap(() ->
                    log.error("vthread task failed", new IllegalStateException("boom")))).get();
        } finally {
            vt.shutdown(); // release-17 baseline: ExecutorService is not AutoCloseable yet
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("request accepted on the carrier thread");
        assertThat(content).contains("traceId=vt1");
    }

    @Test
    void freshVirtualThreadWithoutMdcHasOnlyTheErrorItself(@TempDir Path dir) throws Exception {
        Path file = start(dir);
        Logger log = ctx.getLogger("com.acme.VApp");

        log.info("this INFO lives on the test thread only");
        ExecutorService vt = virtualExecutor();
        try {
            vt.submit(() -> log.error("lonely vthread failure", new IllegalStateException("boom"))).get();
        } finally {
            vt.shutdown();
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("lonely vthread failure");
        assertThat(content).doesNotContain("this INFO lives on the test thread only");
    }

    /**
     * Logback does not hand stacktale a blank thread name even for an unnamed virtual
     * thread — it synthesizes one. Pinned because the story's isolation depends on it, and
     * because it is the reason the shared-bucket hazard (covered in {@code StoryBufferTest})
     * is unreachable through this adapter, while the JUL and Log4j2 paths pass the raw
     * {@code Thread.getName()} and can be blank.
     */
    @Test
    void logbackNamesUnnamedVirtualThreads(@TempDir Path dir) throws Exception {
        Path file = start(dir);
        Logger log = ctx.getLogger("com.acme.VApp");

        ExecutorService vt = virtualExecutor();
        try {
            vt.submit(() -> {
                log.info("handling the request");
                log.error("it failed", new IllegalStateException("boom"));
            }).get();
        } finally {
            vt.shutdown();
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("handling the request");
        // a real, per-thread label — not a shared placeholder
        assertThat(content).doesNotContain("thread=<unnamed>");
        assertThat(content).doesNotContain("story (thread <unnamed>");
    }
}
