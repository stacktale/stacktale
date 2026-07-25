package io.github.gabrielbbaldez.stacktale.junit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Runs a deliberately failing test through a real JUnit Platform launcher and asserts the
 * listener turned it into an {@code st/1} report.
 *
 * <p>The sample classes are plain nested classes whose names do not match Surefire's
 * include patterns, so the outer build never executes them directly — they only run when
 * the inner launcher selects them.
 */
class StacktaleTestListenerTest {

    @Test
    void writesAReportForAFailingTest(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("errors-ai.log");

        run(FailingSample.class, log);

        String written = Files.readString(log);
        assertThat(written).contains("━━━ ERROR #");
        // the root cause leads, as it does for a logged error
        assertThat(written).contains("IllegalStateException: cart total went negative");
        // the test that failed is identifiable without opening the build output
        assertThat(written).contains("test.class=" + FailingSample.class.getName());
        assertThat(written).contains("test.method=totalIsNeverNegative");
    }

    @Test
    void marksTheProductionFrameAsYourCode(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("errors-ai.log");

        run(FailingSample.class, log);

        // appPackages is inferred from the plan, so the frame inside the code under test
        // is the culprit — not the assertion library's internals
        assertThat(Files.readString(log)).contains("← YOUR CODE");
    }

    @Test
    void aPassingRunWritesNothing(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("errors-ai.log");

        run(PassingSample.class, log);

        assertThat(Files.exists(log)).isFalse();
    }

    @Test
    void disabledByPropertyWritesNothing(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("errors-ai.log");
        System.setProperty(StacktaleTestListener.ENABLED_PROPERTY, "false");
        try {
            run(FailingSample.class, log);
        } finally {
            System.clearProperty(StacktaleTestListener.ENABLED_PROPERTY);
        }

        assertThat(Files.exists(log)).isFalse();
    }

    /** Executes {@code sample} on its own launcher with only our listener attached. */
    private void run(Class<?> sample, Path log) {
        System.setProperty(StacktaleTestListener.FILE_PROPERTY, log.toString());
        try {
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(sample))
                    .build();
            // auto-registration would attach a second copy of this listener from
            // META-INF/services and report every failure twice
            Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
                    .enableTestExecutionListenerAutoRegistration(false)
                    .build());
            launcher.execute(request, new StacktaleTestListener());
        } finally {
            System.clearProperty(StacktaleTestListener.FILE_PROPERTY);
        }
    }

    /** Stands in for the code under test — the frame that should be marked as the culprit. */
    static class Cart {
        static int total(int price, int quantity) {
            int total = price * quantity;
            if (total < 0) throw new IllegalStateException("cart total went negative");
            return total;
        }
    }

    static class FailingSample {
        @Test
        void totalIsNeverNegative() {
            Cart.total(Integer.MAX_VALUE, 2); // overflows, then throws
        }
    }

    static class PassingSample {
        @Test
        void totalMultiplies() {
            assertThat(Cart.total(3, 4)).isEqualTo(12);
        }
    }
}
