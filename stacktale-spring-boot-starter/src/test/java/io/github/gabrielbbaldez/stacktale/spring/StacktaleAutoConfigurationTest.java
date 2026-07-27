package io.github.gabrielbbaldez.stacktale.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StacktaleAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StacktaleAutoConfiguration.class));

    @AfterEach
    void detachGlobalAppenders() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(StacktaleAutoConfiguration.AUTO_APPENDER_NAME);
        root.detachAppender(StacktaleAutoConfiguration.APPENDER_NAME); // the manual one some tests register
        Logger requestLogger = ctx.getLogger(StacktaleRequestFilter.REQUEST_LOGGER);
        requestLogger.detachAndStopAllAppenders();
        requestLogger.setAdditive(true); // reset the global logger for the next test
    }

    @Test
    void registersTheAppenderOnTheRootLoggerByDefault() {
        runner.withPropertyValues("stacktale.file=target/starter-test-errors.log").run(context -> {
            assertThat(context).hasSingleBean(StacktaleAppender.class);
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            assertThat(ctx.getLogger(Logger.ROOT_LOGGER_NAME)
                    .getAppender(StacktaleAutoConfiguration.AUTO_APPENDER_NAME)).isNotNull();
            // request logger feeds stacktale only — never the console
            assertThat(ctx.getLogger(StacktaleRequestFilter.REQUEST_LOGGER).isAdditive()).isFalse();
        });
    }

    @Test
    void everyPropertyReachesTheAppender() {
        // guards against the settings-sprawl gap: a property declared but never wired is a
        // silent no-op for the zero-config user (maxReportsPerMinute was exactly that)
        runner.withPropertyValues(
                "stacktale.file=target/props-test.log",
                "stacktale.max-reports-per-minute=42",
                "stacktale.story-size=7",
                "stacktale.emit-reports-to-logger=true").run(context -> {
            io.github.gabrielbbaldez.stacktale.spring.StacktaleProperties props =
                    context.getBean(io.github.gabrielbbaldez.stacktale.spring.StacktaleProperties.class);
            assertThat(props.getMaxReportsPerMinute()).isEqualTo(42);
            assertThat(props.getStorySize()).isEqualTo(7);
            assertThat(props.isEmitReportsToLogger()).isTrue();
            assertThat(context).hasSingleBean(StacktaleAppender.class);
        });
    }

    @Test
    void masterSwitchDisablesEverything() {
        runner.withPropertyValues("stacktale.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(StacktaleAppender.class);
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            assertThat(ctx.getLogger(Logger.ROOT_LOGGER_NAME)
                    .getAppender(StacktaleAutoConfiguration.AUTO_APPENDER_NAME)).isNull();
        });
    }

    @Test
    void freshContextReplacesTheStaleAppenderFromAPreviousContext() {
        // Logback's context is JVM-global and outlives Spring's: run two "applications"
        // back to back and the second must get its OWN appender, not the first one's
        StacktaleAppender[] captured = new StacktaleAppender[2];
        runner.withPropertyValues("stacktale.file=target/ctx-one.log")
                .run(context -> captured[0] = context.getBean(StacktaleAppender.class));
        // note: no detach between runs — that's the point
        runner.withPropertyValues("stacktale.file=target/ctx-two.log")
                .run(context -> captured[1] = context.getBean(StacktaleAppender.class));
        assertThat(captured[1]).isNotSameAs(captured[0]);
        assertThat(captured[0].isStarted()).isFalse(); // stale one was stopped on replacement
    }

    @Test
    void doesNotDoubleRegisterWhenUserAlreadyConfiguredOne() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        StacktaleAppender manual = new StacktaleAppender();
        manual.setContext(ctx);
        manual.setName(StacktaleAutoConfiguration.APPENDER_NAME);
        manual.setFile("target/manual-errors.log");
        manual.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(manual);

        runner.run(context -> {
            assertThat(context.getBean(StacktaleAppender.class)).isSameAs(manual);
            // #56: even when reusing a user-configured appender, request lines must be routed
            // ONLY to stacktale (additivity off) — not leaked to the console via the root logger
            Logger requestLogger = ctx.getLogger(StacktaleRequestFilter.REQUEST_LOGGER);
            assertThat(requestLogger.isAdditive()).isFalse();
            assertThat(requestLogger.getAppender(StacktaleAutoConfiguration.APPENDER_NAME)).isSameAs(manual);
        });
    }

    /**
     * Reported on #62 by a contributor building the WebFlux example: a reactive app with no
     * servlet API on the classpath failed to start with
     * {@code NoClassDefFoundError: jakarta.servlet.Filter}.
     *
     * <p>{@code @ConditionalOnWebApplication(SERVLET)} on the <em>method</em> does not help:
     * Spring has to resolve the bean method's return type — {@code FilterRegistrationBean} —
     * to register the definition, and resolving it loads {@code jakarta.servlet.Filter}. The
     * condition is evaluated too late to prevent that. Servlet-only beans have to sit in a
     * nested class carrying a <em>class-level</em> condition, which is read from bytecode
     * metadata without loading the class.
     *
     * <p>The starter's own test classpath has spring-boot-starter-web on it, which is why
     * this went unnoticed — {@link FilteredClassLoader} is what reproduces a reactive app.
     */
    @Test
    void startsOnAReactiveAppWithNoServletApi() {
        new org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StacktaleAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(jakarta.servlet.Filter.class))
                .withPropertyValues("stacktale.file=target/starter-noservlet-errors.log")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StacktaleAppender.class);
                });
    }

    /**
     * The other half of the reactive fix: moving the filter into a nested configuration
     * class must not stop registering it on a servlet app. Nothing covered this before, so
     * the move could have dropped the filter silently.
     */
    @Test
    void registersTheRequestFilterOnAServletApp() {
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StacktaleAutoConfiguration.class))
                .withPropertyValues("stacktale.file=target/starter-servlet-errors.log")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                });
    }

    @Test
    void requestLoggingFalseSkipsTheFilter() {
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StacktaleAutoConfiguration.class))
                .withPropertyValues("stacktale.file=target/starter-nofilter-errors.log",
                        "stacktale.request-logging=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                });
    }
}
