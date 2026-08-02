package io.github.gabrielbbaldez.stacktale.log4j2;

import io.github.gabrielbbaldez.stacktale.ActivePipeline;
import io.github.gabrielbbaldez.stacktale.LogEventData;
import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import io.github.gabrielbbaldez.stacktale.UncaughtHandler;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.status.StatusLogger;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Log4j2 face of stacktale: adapts {@link LogEvent}s into the framework-agnostic
 * {@link ReportPipeline}, which writes AI-oriented error reports to a separate file
 * while the human log stays untouched.
 *
 * <pre>{@code
 * <Configuration packages="io.github.gabrielbbaldez.stacktale.log4j2">
 *   <Appenders>
 *     <Stacktale name="STACKTALE" appPackages="com.your.app"/>
 *   </Appenders>
 *   <Loggers>
 *     <Root level="info"><AppenderRef ref="STACKTALE"/></Root>
 *   </Loggers>
 * </Configuration>
 * }</pre>
 */
@Plugin(name = "Stacktale", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public final class StacktaleAppender extends AbstractAppender {

    private final ReportPipeline pipeline;
    private final boolean installUncaughtHandler;

    private StacktaleAppender(String name, org.apache.logging.log4j.core.Filter filter,
                              boolean ignoreExceptions, Property[] properties,
                              ReportPipeline pipeline, boolean installUncaughtHandler) {
        // The filter used to be hard-coded null here, which is what made a nested <Filters>
        // element do nothing: AppenderControl.isFilteredByAppender asks the appender for its
        // filter, saw null, and let everything through (#122).
        super(name, filter, null, ignoreExceptions, properties);
        this.pipeline = pipeline;
        this.installUncaughtHandler = installUncaughtHandler;
    }

    @Override
    public void start() {
        super.start();
        // publish for reporters outside the logging path (stacktale-junit) so their
        // reports share this file, this dedup window and this story buffer
        ActivePipeline.register(pipeline);
        if (installUncaughtHandler && pipeline.isActive()) {
            org.apache.logging.log4j.Logger uncaught = LogManager.getLogger(UncaughtHandler.UNCAUGHT_LOGGER);
            UncaughtHandler.install(uncaught::error);
        }
    }

    @Override
    public boolean stop(long timeout, java.util.concurrent.TimeUnit timeUnit) {
        ActivePipeline.unregister(pipeline);
        pipeline.close(); // flush pending repeat counters
        // leaving it installed pins this context, and its sink would go on feeding the
        // pipeline just closed above
        if (installUncaughtHandler) UncaughtHandler.uninstall();
        return super.stop(timeout, timeUnit);
    }

    @Override
    public void append(LogEvent event) {
        try {
            pipeline.process(adapt(event));
        } catch (Throwable t) {
            // pipeline.process never throws; this guards the adaptation itself
        }
    }

    /**
     * The event's ThreadContext as a map, without paying for one when there is nothing in it.
     *
     * <p>{@code ReadOnlyStringMap.toMap()} is {@code new HashMap<>(size())} plus a copy loop
     * and it does that unconditionally — including for an empty context. {@code adapt()} runs
     * on every event, error or not, so that was an allocation on the happy path for every
     * application that never touches the ThreadContext, which is most of them.
     *
     * <p>CONTRIBUTING names the cheap happy path as an invariant, and the Logback adapter
     * protects it explicitly (see {@code context(ILoggingEvent)} there). This adapter did not,
     * so the README's ~110 ns per happy-path event — a Logback-only figure from
     * {@code AppendBenchmark} — was not true for this backend.
     */
    // package-private so the allocation invariant can be asserted directly rather than
    // inferred from a benchmark that CI does not run
    static Map<String, String> contextData(LogEvent event) {
        org.apache.logging.log4j.util.ReadOnlyStringMap data = event.getContextData();
        return data == null || data.isEmpty() ? Map.of() : data.toMap();
    }

    private LogEventData adapt(LogEvent event) {
        org.apache.logging.log4j.message.Message message = event.getMessage();
        Map<String, String> mdc = contextData(event);
        Throwable thrown = event.getThrown();
        String formatted = message.getFormattedMessage();
        // non-parameterized Message types (MapMessage, ObjectMessage, StructuredData…)
        // return null/empty from getFormat() — fall back to the formatted text so the
        // log: line never shows an empty pattern
        String pattern = message.getFormat();
        if (pattern == null || pattern.isEmpty()) pattern = formatted;
        Object[] args = message.getParameters();
        // unlike SLF4J, Log4j2 keeps the trailing throwable inside getParameters() even
        // after extracting it as getThrown() — drop it so args= shows only real values
        if (thrown != null && args != null && args.length > 0 && args[args.length - 1] == thrown) {
            args = java.util.Arrays.copyOf(args, args.length - 1);
        }
        return new LogEventData(
                event.getInstant().getEpochMillisecond(),
                event.getLevel().name(),
                event.getLevel().isMoreSpecificThan(Level.ERROR),
                event.getLoggerName(),
                event.getThreadName(),
                pattern,
                args,
                formatted,
                mdc,
                thrown);
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    /** All attributes optional; names mirror the Logback appender's properties. */
    // Log4j2 2.26+ warns-as-error when a @PluginBuilderAttribute field has no public setter.
    // The plugin system writes these fields directly, so setters would be dead code — each
    // field carries @SuppressWarnings("log4j.public.setter"). (Harmless no-op on older Log4j2.)
    public static final class Builder extends AbstractAppender.Builder<Builder>
            implements org.apache.logging.log4j.core.util.Builder<StacktaleAppender> {

        // `name`, `ignoreExceptions`, the @PluginElement Filter and Property[] all come from
        // AbstractAppender.Builder. Declaring name here again would shadow the inherited one
        // and leave the plugin system with two fields of that name to choose between.
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private String file = "errors-ai.log";
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private String appPackages = "";
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private int storySize = ReportPipeline.Settings.DEFAULT_STORY_SIZE;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private int storyWindowSeconds = ReportPipeline.Settings.DEFAULT_STORY_WINDOW_SECONDS;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private int dedupWindowSeconds = ReportPipeline.Settings.DEFAULT_DEDUP_WINDOW_SECONDS;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private int maxFileSizeMb = ReportPipeline.Settings.DEFAULT_MAX_FILE_SIZE_MB;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private int maxBackups = ReportPipeline.Settings.DEFAULT_MAX_BACKUPS;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private boolean truncateOnStart = false;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private boolean installUncaughtHandler = true;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private boolean reportErrorsWithoutThrowable = true;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private boolean captureExceptionFields = true;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private boolean redactionEnabled = true;
        /** Extra redaction regexes, separated by {@code ;;} (regexes may contain commas). */
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private String redactPatterns = "";
        /** Opt-in: append a stable keyed-hash token to masked values so an AI can still see repetition ({@code ███(a1b2)}). */
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private boolean redactionCorrelation = false;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private String correlationMdcKeys = ReportPipeline.Settings.DEFAULT_CORRELATION_MDC_KEYS;
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private String zone = "";
        /** 0 disables container-echo suppression. */
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private long echoSuppressionMillis = ReportPipeline.Settings.DEFAULT_ECHO_SUPPRESSION_MILLIS;
        /** Comma-separated logger prefixes treated as container echoes, added to the defaults. */
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private String containerLoggers = "";
        /** Also emit each report block as ONE event via logger {@code stacktale.reports}. */
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private boolean emitReportsToLogger = false;
        /** Cap full reports per minute (0 = unlimited); excess errors become a storm line. */
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private int maxReportsPerMinute = 0;
        /** {@code text} (default, densest for an LLM) or {@code json} (st-json/1 NDJSON, for parsers). */
        @SuppressWarnings("log4j.public.setter") @PluginBuilderAttribute private String format = "text";

        @Override
        public StacktaleAppender build() {
            ZoneId zoneId;
            try {
                zoneId = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
            } catch (DateTimeException e) {
                StatusLogger.getLogger().warn("stacktale: invalid zone '{}', using system default", zone);
                zoneId = ZoneId.systemDefault();
            }
            List<Pattern> compiled = new java.util.ArrayList<>();
            if (redactPatterns != null && !redactPatterns.isBlank()) {
                for (String p : redactPatterns.split(";;")) {
                    if (p.isBlank()) continue;
                    try {
                        compiled.add(Pattern.compile(p.trim()));
                    } catch (RuntimeException e) {
                        StatusLogger.getLogger().warn("stacktale: invalid redactPattern '{}' ignored", p);
                    }
                }
            }
            // additive, matching the Logback appender + Spring starter — a custom prefix adds
            // to the Tomcat default rather than replacing it (else echo suppression is lost)
            List<String> containers = new java.util.ArrayList<>(ReportPipeline.Settings.DEFAULT_CONTAINER_LOGGERS);
            if (containerLoggers != null && !containerLoggers.isBlank()) {
                containers.addAll(csv(containerLoggers));
            }
            ReportPipeline.Settings settings = ReportPipeline.Settings.builder()
                    .file(file)
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
                    .containerLoggers(containers)
                    .emitReportsToLogger(emitReportsToLogger)
                    .maxReportsPerMinute(maxReportsPerMinute)
                    .jsonFormat("json".equalsIgnoreCase(format))
                    .build();
            ReportPipeline pipeline = ReportPipeline.create(settings, new ReportPipeline.Host() {
                @Override
                public void selfLog(String message) {
                    LogManager.getLogger(ReportPipeline.SELF_LOGGER).info(message);
                }

                @Override
                public void warn(String message, Throwable t) {
                    StatusLogger.getLogger().warn("stacktale: {}", message, t);
                }

                @Override
                public void emitReport(String block) {
                    LogManager.getLogger(ReportPipeline.REPORTS_LOGGER).info(block);
                }
            });
            return new StacktaleAppender(getName(), getFilter(), isIgnoreExceptions(),
                    getPropertyArray(), pipeline, installUncaughtHandler);
        }

        private static List<String> csv(String s) {
            return io.github.gabrielbbaldez.stacktale.Csv.parse(s);
        }

        public Builder setFile(String file) { this.file = file; return this; }
        public Builder setAppPackages(String appPackages) { this.appPackages = appPackages; return this; }
    }
}
