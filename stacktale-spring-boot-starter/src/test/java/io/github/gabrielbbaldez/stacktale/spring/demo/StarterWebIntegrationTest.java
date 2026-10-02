package io.github.gabrielbbaldez.stacktale.spring.demo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The issue-#3 acceptance test: a demo app whose ONLY stacktale artifact is the starter
 * dependency must produce reports whose story begins with the HTTP request line — zero
 * manual configuration.
 *
 * <p>The request goes through the JDK's HttpClient and the port is read from
 * {@code local.server.port}, not through TestRestTemplate and {@code @LocalServerPort}: Boot 4
 * moved both to other packages and modules, and this test has to compile against every Boot
 * line the starter supports (3.2 through 4.x).
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

    @Value("${local.server.port}")
    private int port;

    @Test
    void reportStoryOpensWithTheHttpRequestLine() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/889/checkout")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(500, 599);

        String content = Files.readString(reportFile, StandardCharsets.UTF_8);
        assertThat(content).contains("GET /orders/889/checkout");            // filter opened the story
        assertThat(content).contains("reserving stock for order 889");        // app INFO in the story
        assertThat(content).contains("IllegalStateException: payment gateway refused order 889");
        assertThat(content).contains("← YOUR CODE");                          // appPackages auto-deduced
        assertThat(content).contains("chargeCard");                           // culprit inside the demo app
    }
}
