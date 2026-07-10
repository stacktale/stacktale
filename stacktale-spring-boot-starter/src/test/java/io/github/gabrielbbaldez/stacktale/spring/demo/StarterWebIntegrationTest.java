package io.github.gabrielbbaldez.stacktale.spring.demo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The issue-#3 acceptance test: a demo app whose ONLY stacktale artifact is the starter
 * dependency must produce reports whose story begins with the HTTP request line — zero
 * manual configuration.
 */
@SpringBootTest(classes = DemoShopApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StarterWebIntegrationTest {

    private static Path reportFile;

    @DynamicPropertySource
    static void stacktaleFile(DynamicPropertyRegistry registry) throws Exception {
        reportFile = Files.createTempDirectory("stacktale-starter-it").resolve("errors-ai.log");
        registry.add("stacktale.file", () -> reportFile.toString());
    }

    @AfterAll
    static void detachGlobalAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender("STACKTALE_AUTO");
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void reportStoryOpensWithTheHttpRequestLine() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/orders/889/checkout", String.class);
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        String content = Files.readString(reportFile, StandardCharsets.UTF_8);
        assertThat(content).contains("GET /orders/889/checkout");            // filter opened the story
        assertThat(content).contains("reserving stock for order 889");        // app INFO in the story
        assertThat(content).contains("IllegalStateException: payment gateway refused order 889");
        assertThat(content).contains("← YOUR CODE");                          // appPackages auto-deduced
        assertThat(content).contains("chargeCard");                           // culprit inside the demo app
    }
}
