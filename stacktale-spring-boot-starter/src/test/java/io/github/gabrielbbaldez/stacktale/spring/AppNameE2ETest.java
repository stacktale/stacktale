package io.github.gabrielbbaldez.stacktale.spring;

import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam PR #150 left uncovered: EnvCollectorTest proves the collector honours a
 * configured name, and the starter's test properties set spring.application.name — but
 * nothing asserted the two were connected. Delete the setAppName call in the
 * auto-configuration and every other test still passes.
 */
class AppNameE2ETest {
    @Test
    void springApplicationNameReachesTheEnvLine() {
        Path f = Path.of("target", "appname-e2e.log");
        try { Files.deleteIfExists(f); } catch (Exception ignored) { }
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StacktaleAutoConfiguration.class))
                .withPropertyValues("spring.application.name=moneta", "stacktale.file=" + f)
                .run(ctx -> {
                    ctx.getBean(StacktaleAppender.class);
                    org.slf4j.LoggerFactory.getLogger("com.acme.Svc")
                            .error("boom", new IllegalStateException("x"));
                    String written = Files.readString(f);
                    assertThat(written).contains("app=moneta");
                });
    }
}
