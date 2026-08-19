package io.github.gabrielbbaldez.stacktale.quarkus.deployment;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots a minimal Quarkus application with only this extension on the classpath (no example app,
 * no HTTP layer) and asserts that stacktale attached zero-config: a SEVERE log with a throwable
 * lands as an st/1 report in the configured file, unwrapped and with the app frame marked.
 */
class StacktaleExtensionTest {

    private static final Path REPORT = Path.of("target/errors-ai-test.log");

    @RegisterExtension
    static final QuarkusUnitTest APP = new QuarkusUnitTest()
            .overrideConfigKey("stacktale.file", REPORT.toString())
            .overrideConfigKey("stacktale.truncate-on-start", "true")
            .overrideConfigKey("stacktale.app-packages", "io.github.gabrielbbaldez.stacktale.quarkus");

    @Test
    void severeLogWithThrowableBecomesAnAiReport() throws Exception {
        Throwable cause = new IllegalStateException("customer cache returned null");
        Logger.getLogger(StacktaleExtensionTest.class.getName())
                .log(Level.SEVERE, "Failed to confirm order 999", cause);

        String report = awaitReport();
        assertContains(report, "IllegalStateException");
        assertContains(report, "customer cache returned null");
        assertContains(report, "← YOUR CODE");
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle),
                () -> "expected report to contain \"" + needle + "\":\n" + haystack);
    }

    private static String awaitReport() throws Exception {
        for (int i = 0; i < 50; i++) {
            if (Files.exists(REPORT)) {
                String content = Files.readString(REPORT);
                if (content.contains("ERROR #")) {
                    return content;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("stacktale wrote no report to " + REPORT.toAbsolutePath());
    }
}
