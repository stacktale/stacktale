package io.github.gabrielbbaldez.stacktale.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Zero-config stacktale for Spring Boot: registers the appender on Logback's root logger
 * at startup — no logback.xml editing — with {@code appPackages} defaulting to the
 * {@code @SpringBootApplication} package. The {@code stacktale.request} logger is wired
 * exclusively to the stacktale appender (additivity off), so HTTP request lines feed the
 * story without ever touching the human console.
 */
@AutoConfiguration
@ConditionalOnClass(LoggerContext.class)
@ConditionalOnProperty(prefix = "stacktale", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StacktaleProperties.class)
@ImportRuntimeHints(StacktaleRuntimeHints.class)
public class StacktaleAutoConfiguration {

    /** A user-configured appender in logback.xml under this name is respected, never replaced. */
    static final String APPENDER_NAME = "STACKTALE";
    /** The auto-configured appender's own name — replaced on every context refresh. */
    static final String AUTO_APPENDER_NAME = "STACKTALE_AUTO";

    @Bean(destroyMethod = "")
    public StacktaleAppender stacktaleAppender(StacktaleProperties props, BeanFactory beanFactory, Environment environment) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
            // a different SLF4J backend is bound; nothing we can do — stay a no-op
            LoggerFactory.getLogger("stacktale")
                    .warn("stacktale starter is on the classpath but Logback is not the SLF4J backend; doing nothing");
            return new StacktaleAppender();
        }
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) instanceof StacktaleAppender existing) {
            // user already configured one in logback.xml — don't double up, but still route the
            // request lines only to it; otherwise they leak to the console via root additivity (#56)
            routeRequestLinesTo(ctx, existing);
            return existing;
        }
        // Logback's context outlives Spring's: a previous application context (test suite,
        // DevTools restart) may have left OUR appender behind with stale configuration.
        // Replace it — never reuse it — so the current context's properties always apply.
        ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> stale =
                root.getAppender(AUTO_APPENDER_NAME);
        if (stale != null) {
            root.detachAppender(stale);
            stale.stop();
        }

        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setName(AUTO_APPENDER_NAME);
        appender.setFile(props.getFile());
        appender.setAppName(
                environment.getProperty("spring.application.name", "")
        );
        appender.setAppPackages(resolveAppPackages(props, beanFactory));
        appender.setStorySize(props.getStorySize());
        appender.setStoryWindowSeconds(props.getStoryWindowSeconds());
        appender.setDedupWindowSeconds(props.getDedupWindowSeconds());
        appender.setMaxFileSizeMb(props.getMaxFileSizeMb());
        appender.setMaxBackups(props.getMaxBackups());
        appender.setTruncateOnStart(props.isTruncateOnStart());
        appender.setInstallUncaughtHandler(props.isInstallUncaughtHandler());
        appender.setReportErrorsWithoutThrowable(props.isReportErrorsWithoutThrowable());
        appender.setCaptureExceptionFields(props.isCaptureExceptionFields());
        appender.setRepro(props.isRepro());
        appender.setRedactionEnabled(props.isRedactionEnabled());
        props.getRedactPatterns().forEach(appender::addRedactPattern);
        appender.setRedactionCorrelation(props.isRedactionCorrelation());
        appender.setCorrelationMdcKeys(props.getCorrelationMdcKeys());
        appender.setZone(props.getZone());
        appender.setEchoSuppressionMillis(props.getEchoSuppressionMillis());
        props.getContainerLoggers().forEach(appender::addContainerLogger);
        appender.setEmitReportsToLogger(props.isEmitReportsToLogger());
        appender.setMaxReportsPerMinute(props.getMaxReportsPerMinute());
        appender.setFormat(props.getFormat());
        appender.start();
        root.addAppender(appender);

        routeRequestLinesTo(ctx, appender);
        return appender;
    }

    /** Route HTTP request lines ONLY to stacktale (additivity off), never to console appenders. */
    private static void routeRequestLinesTo(LoggerContext ctx, StacktaleAppender appender) {
        Logger requestLogger = ctx.getLogger(StacktaleRequestFilter.REQUEST_LOGGER);
        requestLogger.setAdditive(false);
        requestLogger.addAppender(appender);
    }

    /** Detaches the auto-configured appender when the Spring context closes. */
    @Bean
    public org.springframework.beans.factory.DisposableBean stacktaleCleanup(StacktaleAppender appender) {
        return () -> {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
                // the request-logger wiring is always ours — undo it either way (#56)
                ctx.getLogger(StacktaleRequestFilter.REQUEST_LOGGER).detachAppender(appender);
                // but only detach + stop the appender WE created; never the user's own
                if (AUTO_APPENDER_NAME.equals(appender.getName())) {
                    ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
                    appender.stop();
                }
            }
        };
    }

    private String resolveAppPackages(StacktaleProperties props, BeanFactory beanFactory) {
        if (!props.getAppPackages().isBlank()) return props.getAppPackages();
        try {
            if (AutoConfigurationPackages.has(beanFactory)) {
                return String.join(",", AutoConfigurationPackages.get(beanFactory));
            }
        } catch (RuntimeException ignored) {
            // fall through to heuristic mode
        }
        return "";
    }

    /**
     * The servlet filter lives in a nested class, and the servlet condition is on the
     * <em>class</em>, not on the bean method.
     *
     * <p>The distinction matters. {@code @ConditionalOnClass} on a type is evaluated in the
     * PARSE_CONFIGURATION phase, from bytecode metadata, without loading anything — so on a
     * reactive app with no servlet API the enclosing class is never touched and neither
     * {@code FilterRegistrationBean} nor {@link StacktaleRequestFilter} (which
     * {@code implements jakarta.servlet.Filter}) is ever resolved. A condition on the bean
     * method is a REGISTER_BEAN-phase condition: by the time it says no, the method's
     * signature has already been in play. That is the shape that produces
     * {@code NoClassDefFoundError: jakarta.servlet.Filter} in reactive apps, and it is what
     * Spring Boot's own auto-configurations avoid by nesting.
     *
     * <p>Reported on #62 by a contributor building the WebFlux example. Note the current
     * arrangement survives a reactive context in test (see
     * {@code startsOnAReactiveAppWithNoServletApi}) — this is removing the hazard, not
     * chasing a reproduction.
     */
    /**
     * stacktale's own counters as Micrometer meters (#96), when Micrometer is present.
     *
     * <p>Nested behind a class-level condition, like the filter below. Unlike that one this is a
     * precaution rather than a fix for an observed failure: a method-level
     * {@code @ConditionalOnClass} on a bean returning {@code MeterBinder} also starts cleanly
     * without Micrometer on the classpath — I flattened it and the tests stayed green. Spring
     * reads bean metadata with ASM and never loads the return type, so nothing forces the class.
     * The nesting keeps the two optional integrations shaped the same way and does not depend on
     * that remaining true.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        public io.micrometer.core.instrument.binder.MeterBinder stacktaleMetrics(StacktaleAppender appender) {
            return new StacktaleMetrics(appender);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(jakarta.servlet.Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "stacktale", name = "request-logging", havingValue = "true", matchIfMissing = true)
    static class RequestLoggingConfiguration {

        @Bean
        public FilterRegistrationBean<StacktaleRequestFilter> stacktaleRequestFilter(StacktaleAppender appender) {
            FilterRegistrationBean<StacktaleRequestFilter> registration =
                    new FilterRegistrationBean<>(new StacktaleRequestFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10); // early: open the story before the app runs
            return registration;
        }
    }
}
