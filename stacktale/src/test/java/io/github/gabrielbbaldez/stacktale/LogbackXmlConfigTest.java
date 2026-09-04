package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the EXACT adoption path from the README: configuring the appender through
 * logback XML (Joran calls the public setters). If a setter name or type is wrong, this
 * test fails even though programmatic tests pass.
 */
class LogbackXmlConfigTest {

    private LoggerContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop();
    }

    @Test
    void configuresEveryPropertyFromXmlAndReports(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        String xml = """
                <configuration>
                  <appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender">
                    <file>%s</file>
                    <appName>checkout-api</appName>
                    <appVersion>9.9.9</appVersion>
                    <appPackages>com.acme</appPackages>
                    <storySize>10</storySize>
                    <storyWindowSeconds>30</storyWindowSeconds>
                    <dedupWindowSeconds>120</dedupWindowSeconds>
                    <maxFileSizeMb>2</maxFileSizeMb>
                    <maxBackups>3</maxBackups>
                    <truncateOnStart>false</truncateOnStart>
                    <installUncaughtHandler>false</installUncaughtHandler>
                    <reportErrorsWithoutThrowable>true</reportErrorsWithoutThrowable>
                    <captureExceptionFields>true</captureExceptionFields>
                    <repro>true</repro>
                    <redactionEnabled>true</redactionEnabled>
                    <redactPattern>BR\\d{2}-\\d{4}</redactPattern>
                    <redactionCorrelation>true</redactionCorrelation>
                    <correlationMdcKeys>traceId</correlationMdcKeys>
                    <zone>UTC</zone>
                    <echoSuppressionMillis>250</echoSuppressionMillis>
                    <containerLogger>com.acme.container</containerLogger>
                    <maxReportsPerMinute>50</maxReportsPerMinute>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="STACKTALE"/>
                  </root>
                </configuration>
                """.formatted(file.toString().replace("\\", "/"));

        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        JoranConfigurator joran = new JoranConfigurator();
        joran.setContext(ctx);
        joran.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        org.slf4j.Logger log = ctx.getLogger("com.acme.CheckoutService");
        log.info("charging card for order 42");
        log.error("charge failed for order {}", 42, new IllegalStateException("gateway timeout"));

        assertThat(Files.exists(file))
                .withFailMessage("XML-configured appender produced no report file — Joran/setter wiring is broken. Context status: %s",
                        ctx.getStatusManager().getCopyOfStatusList())
                .isTrue();
        assertNoJoranComplaints(ctx);

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("IllegalStateException: gateway timeout");
        assertThat(content).contains("charging card for order 42");   // story flowed through XML config too
        assertThat(content).contains("← YOUR CODE");                  // appPackages applied
        assertThat(content).contains("app=checkout-api 9.9.9");        // appName + appVersion reached EnvCollector

        // `repro` is in the block above for the binding check, and there is nothing further to
        // assert here: the seed comes from stacktale-agent, and with no agent attached it
        // resolves to null and the section is correctly absent. What must not happen is a
        // *report* — the knob is opt-in extra content, not a switch that can suppress one.
        assertThat(content).contains("ERROR #");
    }

    /**
     * The point of this file: a setter-name or type typo must fail here.
     *
     * <p>Joran does not throw on an element it cannot bind — it records a WARN or ERROR on
     * the context and carries on with the property unset. So a test that only asserts on the
     * report file passes happily while a knob silently does nothing, which is exactly how the
     * six properties this test used to omit could have rotted unnoticed.
     *
     * <p>Asserting on the status list rather than on each property's behaviour also means a
     * property added later is covered the moment it appears in the XML above, without anyone
     * having to invent an observable effect for it.
     */
    private static void assertNoJoranComplaints(LoggerContext ctx) {
        List<Status> bad = ctx.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getEffectiveLevel() >= Status.WARN)
                .toList();
        assertThat(bad)
                .withFailMessage("Joran could not bind part of the XML — a setter name or type is wrong: %s", bad)
                .isEmpty();
    }

    @Test
    void jsonFormatIsSelectableFromXml(@TempDir Path dir) throws Exception {
        // `format` is set apart from the block above because it changes the shape of every
        // line, so it cannot share assertions with the text-format test.
        Path file = dir.resolve("errors-ai.log");
        String xml = """
                <configuration>
                  <appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender">
                    <file>%s</file>
                    <appPackages>com.acme</appPackages>
                    <format>json</format>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="STACKTALE"/>
                  </root>
                </configuration>
                """.formatted(file.toString().replace("\\", "/"));

        configure(xml);
        ctx.getLogger("com.acme.CheckoutService")
                .error("charge failed", new IllegalStateException("gateway timeout"));

        assertNoJoranComplaints(ctx);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content)
                .withFailMessage("<format>json</format> did not select the JSON renderer: %s", content)
                .startsWith("{");
        assertThat(content).contains("gateway timeout");
    }

    @Test
    void emitReportsToLoggerIsSettableFromXml(@TempDir Path dir) throws Exception {
        // Also apart: this one routes the report back through logback, so it would appear in
        // the sibling appenders of any test it shared a context with.
        Path file = dir.resolve("errors-ai.log");
        String xml = """
                <configuration>
                  <appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender">
                    <file>%s</file>
                    <appPackages>com.acme</appPackages>
                    <emitReportsToLogger>true</emitReportsToLogger>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="STACKTALE"/>
                  </root>
                </configuration>
                """.formatted(file.toString().replace("\\", "/"));

        configure(xml);
        ctx.getLogger("com.acme.CheckoutService")
                .error("charge failed", new IllegalStateException("gateway timeout"));

        assertNoJoranComplaints(ctx);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("gateway timeout");
    }

    @Test
    void anUnknownPropertyIsReportedByJoran(@TempDir Path dir) throws Exception {
        // The control. Without this, assertNoJoranComplaints could be passing because Joran
        // never complains about anything, and every assertion above would be vacuous.
        Path file = dir.resolve("errors-ai.log");
        String xml = """
                <configuration>
                  <appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender">
                    <file>%s</file>
                    <thisPropertyDoesNotExist>true</thisPropertyDoesNotExist>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="STACKTALE"/>
                  </root>
                </configuration>
                """.formatted(file.toString().replace("\\", "/"));

        configure(xml);

        assertThat(ctx.getStatusManager().getCopyOfStatusList())
                .withFailMessage("Joran accepted an unknown property, so the guard above proves nothing")
                .anyMatch(s -> s.getEffectiveLevel() >= Status.WARN);
    }

    private void configure(String xml) throws Exception {
        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        JoranConfigurator joran = new JoranConfigurator();
        joran.setContext(ctx);
        joran.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
