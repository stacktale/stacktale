package io.github.gabrielbbaldez.stacktale.quarkus.runtime;

import io.github.gabrielbbaldez.stacktale.Csv;
import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import io.github.gabrielbbaldez.stacktale.jul.StacktaleJulHandler;
import io.quarkus.runtime.annotations.Recorder;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * The runtime worker of the extension. A Quarkus {@code @Recorder}'s methods are invoked from a
 * build step, but their bytecode runs at application startup — this is how a Quarkus extension
 * does at boot what a Spring starter does via {@code @AutoConfiguration}, while staying friendly
 * to GraalVM native builds (no classpath scanning at runtime).
 *
 * <p>{@link #install} builds a {@link ReportPipeline.Settings} from the resolved config and
 * attaches a {@link StacktaleJulHandler} to the root JUL logger — which under Quarkus is the
 * JBoss LogManager — so every {@code SEVERE} record becomes an {@code st/1} report and lower
 * levels feed the story. No {@code logging.properties} editing: that is the "zero-config" #82 asks for.
 */
@Recorder
public class StacktaleRecorder {

    public void install(StacktaleConfig config, List<String> deducedAppPackages) {
        if (!config.enabled()) {
            return;
        }

        List<String> appPackages = config.appPackages()
                .map(StacktaleRecorder::splitCsv)
                .filter(list -> !list.isEmpty())
                .orElse(deducedAppPackages);

        ReportPipeline.Settings settings = ReportPipeline.Settings.builder()
                .appPackages(appPackages)
                .file(config.file())
                .storySize(config.storySize())
                .storyWindowMillis(config.storyWindowSeconds() * 1000L)
                .dedupWindowMillis(config.dedupWindowSeconds() * 1000L)
                .maxFileBytes(config.maxFileSizeMb() * 1024L * 1024L)
                .maxBackups(config.maxBackups())
                .truncateOnStart(config.truncateOnStart())
                .reportErrorsWithoutThrowable(config.reportErrorsWithoutThrowable())
                .captureExceptionFields(config.captureExceptionFields())
                .redactionEnabled(config.redactionEnabled())
                .redactPatterns(compilePatterns(config.redactPatterns().orElse(List.of())))
                .redactionCorrelation(config.redactionCorrelation())
                .correlationMdcKeys(Csv.parse(config.correlationMdcKeys()))
                .zone(resolveZone(config.zone()))
                .echoSuppressionMillis(config.echoSuppressionMillis())
                .containerLoggers(mergeContainerLoggers(config.containerLoggers().orElse(List.of())))
                .emitReportsToLogger(config.emitReportsToLogger())
                .maxReportsPerMinute(config.maxReportsPerMinute())
                .jsonFormat("json".equalsIgnoreCase(config.format().trim()))
                .build();

        Logger root = Logger.getLogger("");
        StacktaleJulHandler handler = attached(root);
        if (handler == null) {
            handler = new StacktaleJulHandler(settings, config.installUncaughtHandler());
            root.addHandler(handler);
        }
        if (config.requestLogging()) {
            routeRequestLogger(handler);
        }
    }

    private static StacktaleJulHandler attached(Logger root) {
        for (var h : root.getHandlers()) {
            if (h instanceof StacktaleJulHandler) {
                return (StacktaleJulHandler) h;
            }
        }
        return null;
    }

    private static void routeRequestLogger(StacktaleJulHandler handler) {
        Logger request = Logger.getLogger(StacktaleRequestFilter.REQUEST_LOGGER);
        request.setUseParentHandlers(false);
        request.setLevel(java.util.logging.Level.INFO);
        for (var h : request.getHandlers()) {
            if (h == handler) {
                return;
            }
        }
        request.addHandler(handler);
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        List<Pattern> out = new ArrayList<>();
        for (String pattern : patterns) {
            if (!pattern.isBlank()) {
                try {
                    out.add(Pattern.compile(pattern.trim()));
                } catch (RuntimeException e) {
                    Logger.getLogger(ReportPipeline.SELF_LOGGER)
                            .log(java.util.logging.Level.WARNING,
                                    "invalid stacktale.redact-patterns entry ignored", e);
                }
            }
        }
        return out;
    }

    private static List<String> mergeContainerLoggers(List<String> configured) {
        List<String> out = new ArrayList<>(ReportPipeline.Settings.DEFAULT_CONTAINER_LOGGERS);
        for (String logger : configured) {
            if (!logger.isBlank()) {
                out.add(logger.trim());
            }
        }
        return out;
    }

    private static ZoneId resolveZone(Optional<String> configuredZone) {
        String zone = configuredZone.orElse("");
        try {
            return zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        } catch (DateTimeException e) {
            Logger.getLogger(ReportPipeline.SELF_LOGGER)
                    .log(java.util.logging.Level.WARNING,
                            "invalid stacktale.zone '" + zone + "', falling back to system default", e);
            return ZoneId.systemDefault();
        }
    }
}
