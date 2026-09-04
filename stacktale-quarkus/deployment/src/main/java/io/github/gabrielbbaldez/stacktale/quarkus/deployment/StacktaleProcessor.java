package io.github.gabrielbbaldez.stacktale.quarkus.deployment;

import io.github.gabrielbbaldez.stacktale.quarkus.runtime.StacktaleRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;
import org.jboss.jandex.ClassInfo;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Build-time wiring for the stacktale Quarkus extension. Every method here runs during the build;
 * Quarkus resolves the {@code *BuildItem} inputs/outputs into a graph and executes the steps in
 * dependency order. This is the "assembled in the factory" half of a Quarkus extension — the part
 * a Spring starter has no equivalent for.
 */
public class StacktaleProcessor {

    private static final String FEATURE = "stacktale";
    private static final String REST_CAPABILITY = "io.quarkus.rest";
    private static final String REQUEST_FILTER =
            "io.github.gabrielbbaldez.stacktale.quarkus.runtime.StacktaleRequestFilter";

    /** Registers the extension so it shows up in the startup banner and Dev UI. */
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Records a call to {@link StacktaleRecorder#install} that runs at application startup.
     * {@code @Record(RUNTIME_INIT)} means the recorded bytecode executes when the app boots
     * (config and filesystem available), not when a native image is built.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void install(StacktaleRecorder recorder, ApplicationArchivesBuildItem archives,
                 ApplicationInfoBuildItem app) {
        recorder.install(deduceAppPackages(archives), named(app.getName()), named(app.getVersion()));
    }

    /**
     * {@code quarkus.application.name} and {@code .version} default to the artifact's
     * coordinates, but a build that sets neither leaves the literal {@code <<unset>>} here.
     * Passing that through would put it in every report's {@code env:} line; an empty string
     * lets {@code EnvCollector} fall back the way it does for every other adapter.
     */
    private static String named(String value) {
        return value == null || ApplicationInfoBuildItem.UNSET_VALUE.equals(value) ? "" : value;
    }

    /**
     * Registers the request filter as an unremovable CDI bean, so RESTEasy Reactive discovers its
     * {@code @ServerRequestFilter} method. The filter references the REST API, an optional runtime
     * dependency — apps without a REST layer simply never invoke it.
     */
    @BuildStep
    void registerRequestFilter(Capabilities capabilities, BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        if (capabilities.isPresent(REST_CAPABILITY)) {
            additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(REQUEST_FILTER));
        }
    }

    /**
     * The stacktale report writer opens a file and starts a background flusher at runtime; both
     * must be initialized at run time, never during native image build. This is the native-image
     * counterpart of stacktale's shipped RuntimeHints metadata.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void runtimeInitializedClasses(BuildProducer<RuntimeInitializedClassBuildItem> producer) {
        producer.produce(new RuntimeInitializedClassBuildItem(
                "io.github.gabrielbbaldez.stacktale.ReportPipeline"));
        producer.produce(new RuntimeInitializedClassBuildItem(
                "io.github.gabrielbbaldez.stacktale.jul.StacktaleJulHandler"));
    }

    /**
     * Deduces the application's root package so {@code stacktale.app-packages} can be left unset and
     * stacktale still marks the right frames as {@code ← YOUR CODE}. Falls back to an empty list,
     * which the core treats as "mark nothing" rather than guessing wrong.
     */
    static List<String> deduceAppPackages(ApplicationArchivesBuildItem archives) {
        List<String> packages = archives.getRootArchive().getIndex().getKnownClasses().stream()
                .map(ClassInfo::name)
                .map(name -> name.packagePrefix())
                .filter(pkg -> pkg != null && !pkg.isBlank())
                .filter(pkg -> !isFrameworkPackage(pkg))
                .collect(Collectors.toCollection(ArrayList::new));
        if (!packages.isEmpty()) {
            return List.of(mostCommonRoot(packages));
        }
        return List.of();
    }

    private static boolean isFrameworkPackage(String pkg) {
        return pkg.startsWith("io.github.gabrielbbaldez.stacktale")
                || pkg.startsWith("io.quarkus")
                || pkg.startsWith("jakarta.")
                || pkg.startsWith("java.")
                || pkg.startsWith("org.jboss.")
                || pkg.startsWith("org.junit.");
    }

    private static String mostCommonRoot(List<String> packages) {
        return packages.stream()
                .map(StacktaleProcessor::packageRoot)
                .collect(Collectors.groupingBy(pkg -> pkg, Collectors.counting()))
                .entrySet()
                .stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private static String packageRoot(String pkg) {
        String[] parts = pkg.split("\\.");
        if (parts.length <= 2) {
            return pkg;
        }
        return parts[0] + "." + parts[1] + "." + parts[2];
    }
}
