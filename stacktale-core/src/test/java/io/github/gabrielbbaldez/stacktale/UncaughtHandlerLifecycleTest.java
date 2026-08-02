package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The install/uninstall pair around the JVM-global default handler (#121).
 *
 * <p>This slot is process-wide, so each test restores whatever it found. A leaked handler
 * here would not fail this class — it would fail something unrelated, later.
 */
class UncaughtHandlerLifecycleTest {

    private Thread.UncaughtExceptionHandler original;

    @BeforeEach
    void remember() {
        original = Thread.getDefaultUncaughtExceptionHandler();
    }

    @AfterEach
    void restore() {
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    @Test
    void aRestartRoutesToTheNewSinkRatherThanTheStoppedOne() {
        List<String> firstContext = new ArrayList<>();
        List<String> secondContext = new ArrayList<>();

        UncaughtHandler.install((message, thrown) -> firstContext.add(message));
        // what a Spring DevTools restart, or the second context in a test suite, does
        UncaughtHandler.uninstall();
        UncaughtHandler.install((message, thrown) -> secondContext.add(message));

        fire(new IllegalStateException("boom"));

        // install() used to return early on finding one of ours, so the stopped context's
        // sink kept the slot and no uncaught exception produced a report again
        assertThat(secondContext).hasSize(1);
        assertThat(firstContext).isEmpty();
    }

    @Test
    void installingOverAStaleHandlerKeepsWhateverSatBelowIt() {
        List<String> application = new ArrayList<>();
        Thread.UncaughtExceptionHandler applicationHandler = (t, e) -> application.add(e.getMessage());
        Thread.setDefaultUncaughtExceptionHandler(applicationHandler);

        List<String> reports = new ArrayList<>();
        UncaughtHandler.install((message, thrown) -> reports.add(message));
        UncaughtHandler.install((message, thrown) -> reports.add(message)); // stale one replaced

        fire(new IllegalStateException("boom"));

        // exactly one of ours in the chain, and the application's handler still runs
        assertThat(reports).hasSize(1);
        assertThat(application).containsExactly("boom");
    }

    @Test
    void uninstallPutsBackTheHandlerThatWasThereBefore() {
        Thread.UncaughtExceptionHandler applicationHandler = (t, e) -> { };
        Thread.setDefaultUncaughtExceptionHandler(applicationHandler);

        UncaughtHandler.install((message, thrown) -> { });
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNotSameAs(applicationHandler);

        UncaughtHandler.uninstall();

        // the static slot no longer pins the context behind that sink — in a servlet
        // hot-redeploy it pinned the whole webapp classloader
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(applicationHandler);
    }

    @Test
    void uninstallLeavesAHandlerTheApplicationInstalledAfterUs() {
        List<String> application = new ArrayList<>();
        UncaughtHandler.install((message, thrown) -> { });
        Thread.UncaughtExceptionHandler laterHandler = (t, e) -> application.add(e.getMessage());
        Thread.setDefaultUncaughtExceptionHandler(laterHandler);

        UncaughtHandler.uninstall();

        // that slot belongs to whoever took it last; taking it back would be worse than the leak
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(laterHandler);
    }

    @Test
    void withNoPreviousHandlerTheThrowableIsPrintedRatherThanBouncedBackToTheRootGroup() {
        Thread.setDefaultUncaughtExceptionHandler(null); // the ordinary case: nobody set one

        List<String> reports = new ArrayList<>();
        UncaughtHandler.install((message, thrown) -> reports.add(message));

        java.io.PrintStream realErr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            // Used to delegate to t.getThreadGroup().uncaughtException(...). The root group's
            // last act is to call the default handler — us — so this looped until the stack
            // ended, turning every uncaught exception into a StackOverflowError.
            fire(new IllegalStateException("boom"));
        } finally {
            System.setErr(realErr);
        }

        assertThat(reports).containsExactly("Uncaught exception in thread main");
        assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Exception in thread")
                .contains("boom");
    }

    /** Invokes the current default handler the way the JVM would for a dying thread. */
    private void fire(Throwable t) {
        Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), t);
    }
}
