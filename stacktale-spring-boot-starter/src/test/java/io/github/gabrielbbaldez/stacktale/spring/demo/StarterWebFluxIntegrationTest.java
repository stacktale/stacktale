package io.github.gabrielbbaldez.stacktale.spring.demo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue-#22 acceptance: a WebFlux app whose story survives TWO scheduler hops — the
 * request line planted by the reactive filter and an INFO logged on a boundedElastic
 * thread must appear in the report of an error logged on a parallel thread.
 */
@SpringBootTest(classes = ReactiveDemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.web-application-type=reactive")
class StarterWebFluxIntegrationTest {

    private static Path reportFile;

    @DynamicPropertySource
    static void stacktaleFile(DynamicPropertyRegistry registry) throws Exception {
        reportFile = Files.createTempDirectory("stacktale-webflux-it").resolve("errors-ai.log");
        registry.add("stacktale.file", () -> reportFile.toString());
    }

    @AfterAll
    static void detachGlobalAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender("STACKTALE_AUTO");
    }

    @Autowired
    private WebTestClient client;

    @Test
    void storySurvivesReactorSchedulerHops() throws Exception {
        client.get().uri("/quotes/314").exchange().expectStatus().is5xxServerError();

        String content = Files.readString(reportFile, StandardCharsets.UTF_8);
        assertThat(content).contains("IllegalStateException: pricing feed disconnected");
        assertThat(content).contains("GET /quotes/314");                    // filter opened the story
        assertThat(content).contains("pricing lookup for instrument 314"); // survived hop #1 (boundedElastic)
        assertThat(content).contains("quote requested for instrument 314");
        assertThat(content).contains("traceId=");                           // correlated, not thread-fallback
    }
}
