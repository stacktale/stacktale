package io.github.gabrielbbaldez.stacktale.logback;

import io.github.gabrielbbaldez.stacktale.ActivePipeline;
import io.github.gabrielbbaldez.stacktale.Csv;
import io.github.gabrielbbaldez.stacktale.LogEventData;
import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import io.github.gabrielbbaldez.stacktale.UncaughtHandler;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Logback face of stacktale: a thin adapter that turns {@link ILoggingEvent}s into
 * {@link LogEventData} and hands them to the framework-agnostic {@link ReportPipeline},
 * which writes AI-oriented error reports to a separate file while the human log stays
 * untouched.
 *
 * <p>Guarantee: never throws out of {@link #append} and never blocks the happy path with
 * I/O. If something inside stacktale breaks, it degrades to a no-op.
 */
public final class StacktaleAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private String file = "errors-ai.log";
    private String appName = "";
    private String appVersion = "";
    private String appPackages = "";
    private int storySize = ReportPipeline.Settings.DEFAULT_STORY_SIZE;
    private int storyWindowSeconds = ReportPipeline.Settings.DEFAULT_STORY_WINDOW_SECONDS;
    private int dedupWindowSeconds = ReportPipeline.Settings.DEFAULT_DEDUP_WINDOW_SECONDS;
    private int maxFileSizeMb = ReportPipeline.Settings.DEFAULT_MAX_FILE_SIZE_MB;
    private int maxBackups = ReportPipeline.Settings.DEFAULT_MAX_BACKUPS;
    private boolean truncateOnStart = false;
    private boolean installUncaughtHandler = true;
    private boolean reportErrorsWithoutThrowable = true;
    private boolean captureExceptionFields = true;
    private boolean redactionEnabled = true;
    private final List<String> redactPatterns = new java.util.ArrayList<>();
    private boolean redactionCorrelation = false;
    private String correlationMdcKeys = ReportPipeline.Settings.DEFAULT_CORRELATION_MDC_KEYS;
    private String zone = "";
    private long echoSuppressionMillis = ReportPipeline.Settings.DEFAULT_ECHO_SUPPRESSION_MILLIS;
    private final List<String> containerLoggers =
            new java.util.ArrayList<>(ReportPipeline.Settings.DEFAULT_CONTAINER_LOGGERS);
    private boolean emitReportsToLogger = false;
    private int maxReportsPerMinute = 0;
    private boolean jsonFormat = false;

    private ReportPipeline pipeline;
    /** The JVM hook that drains trailing repeat counters; null when not registered. */
    Thread shutdownDrain;
    private org.slf4j.Logger selfLogger;
    private volatile boolean mdcUnavailable;
    private volatile boolean keyValuesUnavailable;

    @Override
    public void start() {
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        } catch (DateTimeException e) {
            addWarn("invalid zone '" + zone + "', falling back to system default", e);
            zoneId = ZoneId.systemDefault();
        }
        // Log through this appender's own context: during application boot start() runs in
        // the middle of SLF4J initialization, and the global LoggerFactory is not safe yet
        // (and would be the wrong context in multi-context environments).
        selfLogger = getContext() instanceof ch.qos.logback.classic.LoggerContext lc
                ? lc.getLogger(ReportPipeline.SELF_LOGGER)
                : LoggerFactory.getLogger(ReportPipeline.SELF_LOGGER);

        List<Pattern> compiled = new java.util.ArrayList<>();
        for (String p : redactPatterns) {
            try {
                compiled.add(Pattern.compile(p));
            } catch (RuntimeException e) {
                addWarn("invalid redactPattern '" + p + "' ignored", e);
            }
        }

        ReportPipeline.Settings settings = ReportPipeline.Settings.builder()
                .file(file)
                .appName(appName)
                .appVersion(appVersion)
                .appPackages(csv(appPackages))
                .storySize(storySize)
                .storyWindowMillis(storyWindowSeconds * 1000L)
                .dedupWindowMillis(dedupWindowSeconds * 1000L)
                .maxFileBytes(maxFileSizeMb * 1024L * 1024L)
                .maxBackups(maxBackups)
                .truncateOnStart(truncateOnStart)
                .reportErrorsWithoutThrowable(reportErrorsWithoutThrowable)
                .captureExceptionFields(captureExceptionFields)
                .redactionEnabled(redactionEnabled)
                .redactPatterns(compiled)
                .redactionCorrelation(redactionCorrelation)
                .correlationMdcKeys(csv(correlationMdcKeys))
                .zone(zoneId)
                .echoSuppressionMillis(echoSuppressionMillis)
                .containerLoggers(List.copyOf(containerLoggers))
                .emitReportsToLogger(emitReportsToLogger)
                .maxReportsPerMinute(maxReportsPerMinute)
                .jsonFormat(jsonFormat)
                .build();
        org.slf4j.Logger reportsLogger = getContext() instanceof ch.qos.logback.classic.LoggerContext lc
                ? lc.getLogger(ReportPipeline.REPORTS_LOGGER)
                : LoggerFactory.getLogger(ReportPipeline.REPORTS_LOGGER);
        pipeline = ReportPipeline.create(settings, new ReportPipeline.Host() {
            @Override
            public void selfLog(String message) {
                selfLogger.info(message);
            }

            @Override
            public void warn(String message, Throwable t) {
                addWarn(message, t);
            }

            @Override
            public void emitReport(String block) {
                reportsLogger.info(block);
            }
        });
        super.start();
        if (pipeline.isActive()) {
            // absolute: the configured path is relative to the JVM's working directory,
            // which a reader of this line has no way to know
            addInfo("stacktale active → " + java.nio.file.Path.of(file).toAbsolutePath().normalize());
            // publish for reporters outside the logging path (stacktale-junit) so their
            // reports share this file, this dedup window and this story buffer
            ActivePipeline.register(pipeline);
            if (installUncaughtHandler) {
                // context-local, for the same reason selfLogger and reportsLogger are: the
                // global LoggerFactory can point at a different context than the one this
                // appender belongs to, and then the sink writes somewhere else entirely
                ch.qos.logback.classic.Logger uncaught =
                        ((ch.qos.logback.classic.LoggerContext) getContext())
                                .getLogger(UncaughtHandler.UNCAUGHT_LOGGER);
                UncaughtHandler.install(uncaught::error);
            }
            installShutdownDrain();
        }
    }

    /**
     * Flushes the trailing repeat counters when the JVM exits.
     *
     * <p>{@link ReportPipeline#close()} is what drains them, and it runs from {@link #stop()},
     * which Logback calls only on {@code LoggerContext.stop()}. Logback registers no shutdown
     * hook of its own unless {@code <shutdownHook/>} is in the configuration — so for the exact
     * quickstart this project documents, that drain never happened.
     *
     * <p>The counter is not merely missing then, it is <em>stale</em>, which is worse: the file
     * ends with whatever the last burst flush wrote and a reader believes it. Fifty identical
     * errors left {@code repeated 2×} as the final word (#127).
     *
     * <p>Log4j2 drains through {@code DefaultShutdownCallbackRegistry} and JUL through the
     * {@code LogManager} reset, so plain Logback was the only backend where the number lied.
     * Documenting {@code <shutdownHook/>} would have made that asymmetry the user's problem.
     *
     * <p>Safe to pair with Logback's own hook: {@code close()} is idempotent, because
     * {@code drainPending()} advances {@code lastWrittenCount} and a second pass finds nothing.
     */
    private void installShutdownDrain() {
        if (shutdownDrain != null) return; // start() can be called again on a re-configured appender
        ReportPipeline forHook = pipeline;
        Thread hook = new Thread(forHook::close, "stacktale-shutdown-drain");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownDrain = hook;
        } catch (IllegalStateException shuttingDown) {
            // the JVM is already on its way out; there is nothing left to schedule
        }
    }

    /**
     * Removes the hook so a stopped context is not pinned by it — the same leak {@link
     * UncaughtHandler} had. Does nothing when shutdown is already under way, which is the
     * normal case: the hook is running, or Logback's own hook is stopping this context.
     */
    private void removeShutdownDrain() {
        Thread hook = shutdownDrain;
        shutdownDrain = null;
        if (hook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
            // removeShutdownHook throws once shutdown has begun; the hook will simply run
        }
    }

    @Override
    public void stop() {
        removeShutdownDrain();
        if (pipeline != null) {
            ActivePipeline.unregister(pipeline);
            pipeline.close(); // flush pending repeat counters
        }
        // before super.stop(): leaving it installed pins this context, and its sink would go
        // on feeding the pipeline just closed above
        if (installUncaughtHandler) UncaughtHandler.uninstall();
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            pipeline.process(adapt(event));
        } catch (Throwable t) {
            // pipeline.process never throws; this guards the adaptation itself
        }
    }

    private LogEventData adapt(ILoggingEvent event) {
        IThrowableProxy proxy = event.getThrowableProxy();
        Throwable throwable = proxy instanceof ThrowableProxy tp ? tp.getThrowable() : null;
        return new LogEventData(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLevel().isGreaterOrEqual(Level.ERROR),
                event.getLoggerName(),
                event.getThreadName(),
                event.getMessage(),
                event.getArgumentArray(),
                event.getFormattedMessage(),
                context(event),
                throwable);
    }

    /**
     * The event's structured context: MDC plus any SLF4J 2.0 key-value pairs
     * ({@code log.atError().addKeyValue("orderId", 889)…}), merged into one map so they
     * render (the {@code mdc:} line), redact and correlate exactly like MDC. MDC wins on a
     * key collision. Fast path — the overwhelmingly common no-key-values case returns the
     * MDC map itself with no extra allocation.
     */
    private Map<String, String> context(ILoggingEvent event) {
        Map<String, String> mdc = safeMdc(event);
        List<KeyValuePair> pairs = safeKeyValuePairs(event);
        if (pairs.isEmpty()) return mdc;
        Map<String, String> merged = new java.util.LinkedHashMap<>(mdc);
        for (KeyValuePair pair : pairs) {
            if (pair != null && pair.key != null) merged.putIfAbsent(pair.key, String.valueOf(pair.value));
        }
        return merged;
    }

    /**
     * {@link ILoggingEvent#getMDCPropertyMap()} can throw on hand-built logger contexts
     * (no MDCAdapter installed). MDC is optional enrichment — never let it break the
     * pipeline, and cache the failure: filling one NPE stack trace per event costs
     * microseconds and would silently dominate the happy path (found by AppendBenchmark).
     */
    private Map<String, String> safeMdc(ILoggingEvent event) {
        if (mdcUnavailable) return Map.of();
        try {
            Map<String, String> mdc = event.getMDCPropertyMap();
            return mdc == null ? Map.of() : mdc;
        } catch (Throwable t) {
            mdcUnavailable = true;
            return Map.of();
        }
    }

    /**
     * {@link ILoggingEvent#getKeyValuePairs()} is null when none were added and, like MDC,
     * can be absent on unusual event implementations — degrade to empty, cache the failure,
     * never throw out of the happy path.
     */
    private List<KeyValuePair> safeKeyValuePairs(ILoggingEvent event) {
        if (keyValuesUnavailable) return List.of();
        try {
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            return pairs == null ? List.of() : pairs;
        } catch (Throwable t) {
            keyValuesUnavailable = true;
            return List.of();
        }
    }

    private static List<String> csv(String s) {
        return Csv.parse(s);
    }

    // --- Logback config setters ---

    public void setFile(String file) { this.file = file; }
    public void setAppName(String appName) {
        this.appName = appName;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public void setAppPackages(String appPackages) { this.appPackages = appPackages; }

    public void setStorySize(int storySize) { this.storySize = storySize; }

    public void setStoryWindowSeconds(int storyWindowSeconds) { this.storyWindowSeconds = storyWindowSeconds; }

    public void setDedupWindowSeconds(int dedupWindowSeconds) { this.dedupWindowSeconds = dedupWindowSeconds; }

    public void setMaxFileSizeMb(int maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }

    public void setMaxBackups(int maxBackups) { this.maxBackups = maxBackups; }

    public void setTruncateOnStart(boolean truncateOnStart) { this.truncateOnStart = truncateOnStart; }

    public void setInstallUncaughtHandler(boolean installUncaughtHandler) { this.installUncaughtHandler = installUncaughtHandler; }

    public void setReportErrorsWithoutThrowable(boolean reportErrorsWithoutThrowable) { this.reportErrorsWithoutThrowable = reportErrorsWithoutThrowable; }

    public void setCaptureExceptionFields(boolean captureExceptionFields) { this.captureExceptionFields = captureExceptionFields; }

    public void setRedactionEnabled(boolean redactionEnabled) { this.redactionEnabled = redactionEnabled; }

    /** Joran calls this once per {@code <redactPattern>} element in logback.xml. */
    public void addRedactPattern(String pattern) { this.redactPatterns.add(pattern); }

    /** Opt-in: append a stable keyed-hash token to masked values so an AI can still see repetition ({@code ███(a1b2)}). */
    public void setRedactionCorrelation(boolean redactionCorrelation) { this.redactionCorrelation = redactionCorrelation; }

    public void setCorrelationMdcKeys(String correlationMdcKeys) { this.correlationMdcKeys = correlationMdcKeys; }

    public void setZone(String zone) { this.zone = zone; }

    /** 0 disables container-echo suppression. */
    public void setEchoSuppressionMillis(long echoSuppressionMillis) { this.echoSuppressionMillis = echoSuppressionMillis; }

    /** Joran calls this once per {@code <containerLogger>} element (adds to the defaults). */
    public void addContainerLogger(String prefix) { this.containerLoggers.add(prefix); }

    /** Also emit each report block as ONE event via logger {@code stacktale.reports} (for shippers). */
    public void setEmitReportsToLogger(boolean emitReportsToLogger) { this.emitReportsToLogger = emitReportsToLogger; }

    /** Cap full reports per minute (0 = unlimited); excess errors become a storm line. */
    public void setMaxReportsPerMinute(int maxReportsPerMinute) { this.maxReportsPerMinute = maxReportsPerMinute; }

    /** {@code text} (default, densest for an LLM) or {@code json} (st-json/1 NDJSON, for parsers). */
    public void setFormat(String format) { this.jsonFormat = "json".equalsIgnoreCase(format); }
}
