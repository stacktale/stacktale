package io.github.gabrielbbaldez.stacktale.junit;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Optional companion to {@link StacktaleTestListener}: gives a failing test's report the story
 * of what the test actually logged.
 *
 * <p>Without it, a test that sets a correlation key gets a report containing one story line —
 * itself. The listener is notified after the test method returns, when the MDC is already
 * unwound, so the synthetic failure event carries no key and looks in the thread bucket, while
 * everything the test logged went to the {@code traceId} bucket. Tests that never touch the
 * MDC are unaffected, which is most unit tests; this bites exactly where the story is worth
 * most — an integration test with a traceId, or anything exercising request-scoped code.
 *
 * <p>Register it either way:
 *
 * <pre>{@code
 * @ExtendWith(StacktaleExtension.class)
 * class CheckoutIT { … }
 * }</pre>
 *
 * <p>or once for the whole build, in {@code junit-platform.properties}:
 *
 * <pre>
 * junit.jupiter.extensions.autodetection.enabled = true
 * </pre>
 *
 * <p>It stays opt-in on purpose. The zero-config listener is the headline feature and must
 * behave exactly as it does today when this class is not registered — or not on the classpath
 * at all, which is the case for any project that does not depend on Jupiter.
 */
public final class StacktaleExtension implements Extension, AfterTestExecutionCallback {

    /**
     * Snapshots the MDC the instant the test body ends.
     *
     * <p>{@code AfterTestExecutionCallback} rather than {@code TestWatcher}: this runs before
     * {@code @AfterEach}, so the MDC is exactly as the test left it. A watcher fires after the
     * teardown callbacks, and a suite that clears the MDC in {@code @AfterEach} — the correct
     * thing to do — would have nothing left to capture.
     */
    @Override
    public void afterTestExecution(ExtensionContext context) {
        // Only a failing test produces a report, so only a failing test needs its MDC kept.
        if (context.getExecutionException().isEmpty()) return;
        TestMdc.remember(context.getUniqueId(), TestMdc.snapshot());
    }
}
