package io.github.gabrielbbaldez.stacktale.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stacktale's own counters, published as meters (#96).
 *
 * <p>An error reporter is the one component whose failure is invisible by construction: the way
 * it would tell you something broke is the thing that broke. These meters are how an operator
 * finds out anyway.
 */
class StacktaleMetricsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StacktaleAutoConfiguration.class));

    @Test
    void theMetersFollowWhatThePipelineActuallyWrote() {
        Path file = Path.of("target", "metrics-test-errors.log");
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // a leftover from a previous run only affects the file, not the counters
        }

        runner.withPropertyValues("stacktale.file=" + file).run(context -> {
            MeterRegistry registry = new SimpleMeterRegistry();
            context.getBean(MeterBinder.class).bindTo(registry);

            // nothing has happened yet: registered, readable, and honest about being at zero
            assertThat(registry.get("stacktale.reports").functionCounter().count()).isZero();
            assertThat(registry.get("stacktale.active").gauge().value()).isEqualTo(1);
            assertThat(registry.get("stacktale.parked").gauge().value()).isZero();

            org.slf4j.LoggerFactory.getLogger("com.acme.Svc")
                    .error("charge failed", new IllegalStateException("gateway timeout"));

            assertThat(registry.get("stacktale.reports").functionCounter().count()).isEqualTo(1);
            assertThat(registry.get("stacktale.failures").functionCounter().count()).isZero();
        });
    }

    /**
     * Micrometer is an optional dependency, so the common case is an application without it —
     * and it has to start exactly as before, with the appender still in place.
     *
     * <p>This does not prove the nesting is what makes that work: flattening the binder to a
     * method-level {@code @ConditionalOnClass} passes here too, because Spring reads bean
     * metadata with ASM and never loads the return type. What it pins is the behaviour users
     * depend on, whichever arrangement provides it.
     */
    @Test
    void anApplicationWithoutMicrometerStartsWithoutIt() {
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .withPropertyValues("stacktale.file=target/metrics-absent-errors.log")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender.class);
                });
    }
}
