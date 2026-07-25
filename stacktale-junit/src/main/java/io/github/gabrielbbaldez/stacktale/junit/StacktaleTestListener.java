package io.github.gabrielbbaldez.stacktale.junit;

import io.github.gabrielbbaldez.stacktale.ActivePipeline;
import io.github.gabrielbbaldez.stacktale.LogEventData;
import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a failing test into an {@code errors-ai.log} report.
 *
 * <p>A report is normally produced by an appender when the application logs at ERROR. A
 * JUnit failure never gets there: the assertion error is caught by the engine, so nothing
 * is logged and nothing is written. That left the MCP fix-loop — which tells an agent to
 * "re-run the app or tests, then ask what changed" — blind to a red build.
 *
 * <p>This listener closes that gap. It is discovered automatically through
 * {@code META-INF/services}, so Surefire, Gradle and the IDE pick it up with no
 * configuration, and it feeds failures through the same {@link ReportPipeline} the
 * application's appender is using when there is one ({@link ActivePipeline}) — which is
 * what lets the report carry the <em>story</em>: the lines the code logged on that thread
 * before it failed. With no appender configured, a test-scoped dependency on this module
 * alone still produces reports, from a pipeline of its own.
 *
 * <p>Nothing here may fail a build. Every entry point swallows its own errors: a broken
 * reporter is a lost report, never a red test that should have been green.
 */
public final class StacktaleTestListener implements TestExecutionListener {

    /** {@code -Dstacktale.junit.enabled=false} turns the listener off entirely. */
    public static final String ENABLED_PROPERTY = "stacktale.junit.enabled";
    /** {@code -Dstacktale.junit.file=…} overrides where a self-created pipeline writes. */
    public static final String FILE_PROPERTY = "stacktale.junit.file";
    /** {@code -Dstacktale.junit.appPackages=a.b,c.d} overrides the inferred app packages. */
    public static final String APP_PACKAGES_PROPERTY = "stacktale.junit.appPackages";

    private static final String DEFAULT_FILE = "errors-ai.log";

    /** Non-null only when no adapter was running and we had to build our own. */
    private ReportPipeline ownPipeline;
    private List<String> appPackages = List.of();
    private boolean enabled = true;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        try {
            enabled = !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY));
            if (!enabled) return;
            appPackages = configuredAppPackages().orElseGet(() -> inferAppPackages(testPlan));
        } catch (Throwable t) {
            enabled = false; // a reporter that cannot start must not keep trying
        }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        try {
            if (!enabled || result.getStatus() != TestExecutionResult.Status.FAILED) return;
            Throwable throwable = result.getThrowable().orElse(null);
            ReportPipeline pipeline = pipeline();
            if (pipeline == null) return;
            pipeline.process(event(identifier, throwable));
        } catch (Throwable t) {
            // a failed report must never turn a failing test into a build error of ours
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        try {
            // only ours to close — an adapter's pipeline is closed by the adapter, and
            // closing it here would drop the repeat counters the application still owes
            if (ownPipeline != null) {
                ownPipeline.close();
                ActivePipeline.unregister(ownPipeline);
                ownPipeline = null;
            }
        } catch (Throwable t) {
            // nothing useful left to do at the end of a run
        }
    }

    private LogEventData event(TestIdentifier identifier, Throwable throwable) {
        String testClass = className(identifier).orElse("UnknownTest");
        String displayName = identifier.getDisplayName();
        return new LogEventData(
                System.currentTimeMillis(),
                "ERROR",
                true,
                // the test class as the logger: the report reads like one the class emitted,
                // and `logger=` stays useful for locating the source
                testClass,
                Thread.currentThread().getName(),
                "test failed: {}",
                new Object[]{displayName},
                "test failed: " + displayName,
                context(identifier, testClass, displayName),
                throwable);
    }

    /** Test coordinates as MDC, so they render on the report's {@code mdc:} line. */
    private Map<String, String> context(TestIdentifier identifier, String testClass, String displayName) {
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put("test.class", testClass);
        methodName(identifier).ifPresent(m -> mdc.put("test.method", m));
        mdc.put("test.displayName", displayName);
        Set<TestTag> tags = identifier.getTags();
        if (!tags.isEmpty()) {
            List<String> names = new ArrayList<>(tags.size());
            for (TestTag tag : tags) names.add(tag.getName());
            mdc.put("test.tags", String.join(",", names));
        }
        return mdc;
    }

    /**
     * The application's pipeline when an adapter is running — that one already knows the
     * app's packages and, crucially, holds the story buffer these events belong to.
     * Otherwise one of ours, built on first use so a run with no failures costs nothing.
     */
    private ReportPipeline pipeline() {
        ReportPipeline active = ActivePipeline.current();
        if (active != null) return active;
        if (ownPipeline == null) ownPipeline = createOwnPipeline();
        return ownPipeline.isActive() ? ownPipeline : null;
    }

    private ReportPipeline createOwnPipeline() {
        String file = System.getProperty(FILE_PROPERTY, DEFAULT_FILE);
        ReportPipeline.Settings settings = ReportPipeline.Settings.builder()
                .file(file)
                .appPackages(appPackages)
                .build();
        ReportPipeline pipeline = ReportPipeline.create(settings, new ReportPipeline.Host() {
            @Override
            public void selfLog(String message) {
                // the build already prints the failure; a second voice on stdout is noise
            }

            @Override
            public void warn(String message, Throwable t) {
                System.err.println("[stacktale-junit] " + message);
            }

            @Override
            public void emitReport(String block) {
                // emitReportsToLogger is a shipping concern for a running app, not a test run
            }
        });
        // an app that starts its logging later in the run should share this one rather
        // than open a second writer on the same file
        ActivePipeline.register(pipeline);
        return pipeline;
    }

    private java.util.Optional<List<String>> configuredAppPackages() {
        String configured = System.getProperty(APP_PACKAGES_PROPERTY);
        if (configured == null || configured.isBlank()) return java.util.Optional.empty();
        List<String> packages = new ArrayList<>();
        for (String part : configured.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) packages.add(trimmed);
        }
        return packages.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(packages);
    }

    /**
     * The deepest package every test in the plan shares — {@code com.acme} for tests in
     * {@code com.acme.orders} and {@code com.acme.billing}. Marking {@code ← YOUR CODE}
     * needs the application's packages, and the whole plan is the best evidence available
     * before anything has run. Taken from the plan rather than from the first failure, so
     * the answer does not depend on which test happens to break.
     */
    private List<String> inferAppPackages(TestPlan testPlan) {
        List<String> packages = new ArrayList<>();
        for (TestIdentifier root : testPlan.getRoots()) collectPackages(testPlan, root, packages);
        String common = longestCommonPackage(packages);
        return common.isEmpty() ? List.of() : List.of(common);
    }

    private void collectPackages(TestPlan testPlan, TestIdentifier identifier, List<String> into) {
        className(identifier).ifPresent(name -> {
            int lastDot = name.lastIndexOf('.');
            if (lastDot > 0) into.add(name.substring(0, lastDot));
        });
        for (TestIdentifier child : testPlan.getChildren(identifier)) collectPackages(testPlan, child, into);
    }

    private String longestCommonPackage(List<String> packages) {
        if (packages.isEmpty()) return "";
        String[] common = packages.get(0).split("\\.");
        int depth = common.length;
        for (String candidate : packages) {
            String[] parts = candidate.split("\\.");
            int shared = 0;
            while (shared < depth && shared < parts.length && common[shared].equals(parts[shared])) shared++;
            depth = shared;
            if (depth == 0) return "";
        }
        return String.join(".", java.util.Arrays.copyOf(common, depth));
    }

    private java.util.Optional<String> className(TestIdentifier identifier) {
        TestSource source = identifier.getSource().orElse(null);
        if (source instanceof MethodSource method) return java.util.Optional.of(method.getClassName());
        if (source instanceof ClassSource type) return java.util.Optional.of(type.getClassName());
        return java.util.Optional.empty();
    }

    private java.util.Optional<String> methodName(TestIdentifier identifier) {
        TestSource source = identifier.getSource().orElse(null);
        if (source instanceof MethodSource method) return java.util.Optional.of(method.getMethodName());
        return java.util.Optional.empty();
    }
}
