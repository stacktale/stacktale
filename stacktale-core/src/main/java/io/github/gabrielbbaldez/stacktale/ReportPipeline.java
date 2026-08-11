package io.github.gabrielbbaldez.stacktale;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The framework-agnostic heart of stacktale: feed it every log event (as
 * {@link LogEventData}) and it maintains the story, decides which errors deserve a
 * report, and writes st/ blocks to the report file. Logging-framework appenders are thin
 * adapters around this class.
 *
 * <p>Guarantees: {@link #process} never throws, and a pipeline whose configuration is
 * broken (e.g. an invalid file path) degrades to a no-op instead of failing the host.
 */
public final class ReportPipeline {

    /** Logger name used for stacktale's own announce/pointer lines (skipped by the pipeline). */
    public static final String SELF_LOGGER = "stacktale";

    /** Logger that carries whole report blocks to aggregators when {@code emitReportsToLogger} is on. */
    public static final String REPORTS_LOGGER = "stacktale.reports";

    /** All knobs, framework-neutral. Times in millis, sizes in bytes. */
    public record Settings(
            String file,
            String appName,
            String appVersion,
            List<String> appPackages,
            int storySize,
            long storyWindowMillis,
            long dedupWindowMillis,
            long maxFileBytes,
            int maxBackups,
            boolean truncateOnStart,
            boolean reportErrorsWithoutThrowable,
            boolean captureExceptionFields,
            boolean repro,
            boolean redactionEnabled,
            List<Pattern> redactPatterns,
            boolean redactionCorrelation,
            List<String> correlationMdcKeys,
            ZoneId zone,
            long echoSuppressionMillis,
            List<String> containerLoggers,
            boolean emitReportsToLogger,
            int maxReportsPerMinute,
            boolean jsonFormat
    ) {

        /** Default logger prefixes whose errors are re-logs of an exception the app already reported. */
        public static final List<String> DEFAULT_CONTAINER_LOGGERS =
                List.of("org.apache.catalina.core.ContainerBase");

        // Defaults, one source of truth — referenced by the Builder AND every framework
        // appender/property class so a change can't drift between backends. Human units
        // (seconds/MB) where the appenders expose them; the Builder converts to millis/bytes.
        public static final int DEFAULT_STORY_SIZE = 15;
        public static final int DEFAULT_STORY_WINDOW_SECONDS = 60;
        public static final int DEFAULT_DEDUP_WINDOW_SECONDS = 300;
        public static final int DEFAULT_MAX_FILE_SIZE_MB = 5;
        public static final int DEFAULT_MAX_BACKUPS = 1;
        public static final long DEFAULT_ECHO_SUPPRESSION_MILLIS = 2000;
        /**
         * Keys checked, in order, to group a story by request rather than by thread.
         *
         * <p>{@code traceId} is the Micrometer/Spring Boot spelling. {@code trace_id} is what
         * the OpenTelemetry Java agent injects — a different string, and the single most
         * common production JVM setup there is; without it an OTel-instrumented app silently
         * falls back to per-thread correlation, which is exactly wrong on a thread pool.
         *
         * <p>{@code span_id} is deliberately absent: it changes per span within a request, so
         * keying on it would shard one request's story into fragments.
         */
        public static final String DEFAULT_CORRELATION_MDC_KEYS =
                "traceId,trace_id,correlationId,requestId";

        public static Builder builder() {
            return new Builder();
        }

        /**
         * Fluent builder for {@link Settings}: the framework appenders assemble config
         * through named methods, so adding a knob never risks a positional-argument
         * mistake and every default lives in exactly one place. All times are millis,
         * sizes are bytes (framework-neutral).
         */
        public static final class Builder {
            private String file = "errors-ai.log";
            private String appName = "";
            private String appVersion = "";
            private List<String> appPackages = List.of();
            private int storySize = DEFAULT_STORY_SIZE;
            private long storyWindowMillis = DEFAULT_STORY_WINDOW_SECONDS * 1000L;
            private long dedupWindowMillis = DEFAULT_DEDUP_WINDOW_SECONDS * 1000L;
            private long maxFileBytes = DEFAULT_MAX_FILE_SIZE_MB * 1024L * 1024L;
            private int maxBackups = DEFAULT_MAX_BACKUPS;
            private boolean truncateOnStart = false;
            private boolean reportErrorsWithoutThrowable = true;
            private boolean captureExceptionFields = true;
        /**
         * Off by default, and it should stay that way for most projects. The repro seed is the
         * only section that renders argument values against a named signature, which is a
         * bigger privacy surface than the rest of a report put together.
         */
        private boolean repro = false;
            private boolean redactionEnabled = true;
            private List<Pattern> redactPatterns = List.of();
            private boolean redactionCorrelation = false;
            private List<String> correlationMdcKeys = Csv.parse(DEFAULT_CORRELATION_MDC_KEYS);
            private ZoneId zone = ZoneId.systemDefault();
            private long echoSuppressionMillis = DEFAULT_ECHO_SUPPRESSION_MILLIS;
            private List<String> containerLoggers = DEFAULT_CONTAINER_LOGGERS;
            private boolean emitReportsToLogger = false;
            private int maxReportsPerMinute = 0;
            private boolean jsonFormat = false;

            public Builder file(String v) { this.file = v; return this; }
            public Builder appName(String v) {
                this.appName = v;
                return this;
            }

            public Builder appVersion(String v) {
                this.appVersion = v;
                return this;
            }
            public Builder appPackages(List<String> v) { this.appPackages = v; return this; }
            public Builder storySize(int v) { this.storySize = v; return this; }
            public Builder storyWindowMillis(long v) { this.storyWindowMillis = v; return this; }
            public Builder dedupWindowMillis(long v) { this.dedupWindowMillis = v; return this; }
            public Builder maxFileBytes(long v) { this.maxFileBytes = v; return this; }
            public Builder maxBackups(int v) { this.maxBackups = v; return this; }
            public Builder truncateOnStart(boolean v) { this.truncateOnStart = v; return this; }
            public Builder reportErrorsWithoutThrowable(boolean v) { this.reportErrorsWithoutThrowable = v; return this; }
            public Builder captureExceptionFields(boolean v) { this.captureExceptionFields = v; return this; }
        public Builder repro(boolean v) { this.repro = v; return this; }
            public Builder redactionEnabled(boolean v) { this.redactionEnabled = v; return this; }
            public Builder redactPatterns(List<Pattern> v) { this.redactPatterns = v; return this; }
            public Builder redactionCorrelation(boolean v) { this.redactionCorrelation = v; return this; }
            public Builder correlationMdcKeys(List<String> v) { this.correlationMdcKeys = v; return this; }
            public Builder zone(ZoneId v) { this.zone = v; return this; }
            public Builder echoSuppressionMillis(long v) { this.echoSuppressionMillis = v; return this; }
            public Builder containerLoggers(List<String> v) { this.containerLoggers = v; return this; }
            public Builder emitReportsToLogger(boolean v) { this.emitReportsToLogger = v; return this; }
            public Builder maxReportsPerMinute(int v) { this.maxReportsPerMinute = v; return this; }
            public Builder jsonFormat(boolean v) { this.jsonFormat = v; return this; }

            public Settings build() {
                return new Settings(file, appName,appVersion,appPackages, storySize, storyWindowMillis, dedupWindowMillis,
                        maxFileBytes, maxBackups, truncateOnStart, reportErrorsWithoutThrowable,
                        captureExceptionFields, repro, redactionEnabled, redactPatterns, redactionCorrelation,
                        correlationMdcKeys, zone,
                        echoSuppressionMillis, containerLoggers, emitReportsToLogger, maxReportsPerMinute,
                        jsonFormat);
            }
        }
    }

    /** Callbacks into the hosting logging framework. */
    public interface Host {
        /** Emit one line through the framework's normal pipeline (logger {@code stacktale}). */
        void selfLog(String message);

        /** Report an internal stacktale problem through the framework's status/warn channel. */
        void warn(String message, Throwable t);

        /**
         * Carry a whole report block as ONE log event through logger
         * {@link #REPORTS_LOGGER} — existing shippers (Loki, ELK, CloudWatch agents)
         * pick it up with zero stacktale-specific infrastructure.
         */
        default void emitReport(String block) {}
    }

    private final Settings settings;
    private final Host host;
    private final StoryBuffer storyBuffer;
    private final StackDistiller distiller;
    private final Deduper deduper;
    private final StormLimiter stormLimiter;
    private final EnvCollector env;
    private final Renderer renderer;
    private final ReportWriter writer; // null = broken config, pipeline is a no-op
    private final AtomicBoolean warnedOnce = new AtomicBoolean();
    private volatile String absolutePath;

    /**
     * Consecutive failures on the write path, and the switch they trip.
     *
     * <p>A rollback re-arms the dedup window so the next occurrence gets a fresh chance —
     * right when the failure is transient, wrong when it is not. Against a destination that
     * will never accept a write, every error paid for a full distill, a reflective field
     * extraction and a render before failing again, forever, with one warning at the start.
     * After this many in a row the pipeline parks itself: still never throwing, but no
     * longer burning the application's CPU to produce nothing.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean parked;
    private final AtomicBoolean announced = new AtomicBoolean();
    /**
     * When a logical thread last produced a full report — used to suppress container echoes.
     * Keyed by the event's thread NAME (not the physical thread) so it stays correct under
     * Logback AsyncAppender, where every event is processed on one worker thread. Bounded LRU.
     */
    private final Map<String, Long> lastReportByThread =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
                    return size() > 256;
                }
            };

    private ReportPipeline(Settings settings, Host host, ReportWriter writer, Renderer renderer) {
        this(settings, host, writer, renderer, System::currentTimeMillis);
    }

    private ReportPipeline(Settings settings, Host host, ReportWriter writer, Renderer renderer,
                           LongSupplier clock) {
        this.settings = settings;
        this.host = host;
        this.renderer = renderer;
        this.writer = writer;
        this.storyBuffer = new StoryBuffer(settings.storySize(), settings.storyWindowMillis(),
                settings.correlationMdcKeys(), 200);
        this.distiller = new StackDistiller(settings.appPackages());
        this.deduper = new Deduper(settings.dedupWindowMillis(), 60_000, clock);
        this.stormLimiter = settings.maxReportsPerMinute() > 0
                ? new StormLimiter(settings.maxReportsPerMinute(), 60_000, 10_000, clock)
                : StormLimiter.disabled();
        this.env = new EnvCollector(Thread.currentThread().getContextClassLoader(),
                settings.appName(),
                settings.appVersion());
    }

    /** Never throws: a broken configuration produces a warned, no-op pipeline. */
    public static ReportPipeline create(Settings settings, Host host) {
        Redactor redactor = settings.redactionEnabled()
                ? Redactor.withDefaults(settings.redactPatterns(), settings.redactionCorrelation())
                : Redactor.disabled();
        Renderer renderer = settings.jsonFormat()
                ? new JsonReportRenderer(settings.zone(), redactor)
                : new ReportRenderer(settings.zone(), redactor);
        ReportWriter writer;
        try {
            String marker = renderer.sessionMarker(System.currentTimeMillis(), ProcessHandle.current().pid());
            writer = new ReportWriter(Path.of(settings.file()), settings.maxFileBytes(), renderer.fileHeader(),
                    marker, settings.truncateOnStart(), settings.maxBackups(), host::warn);
        } catch (RuntimeException e) {
            host.warn("invalid report file '" + settings.file() + "', stacktale disabled", e);
            writer = null;
        }
        return new ReportPipeline(settings, host, writer, renderer);
    }

    /**
     * Test seam: a pipeline over a caller-supplied writer and renderer.
     *
     * <p>The rollback/confirm protocol's interesting branches are the ones where a write
     * fails, and {@link #create} cannot produce a pipeline that is both active and unable to
     * write — {@code ReportWriter}'s constructor probes the destination and a failed probe
     * turns into {@code isActive() == false}. Injecting the collaborators is the only way to
     * reach those branches from a unit test.
     *
     * <p>{@code clock} drives the {@link Deduper} and {@link StormLimiter} windows, which are
     * minutes long — the same injection {@code DeduperTest} and {@code StormLimiterTest}
     * already use, so a test can cross a window without sleeping through it.
     */
    static ReportPipeline forTesting(Settings settings, Host host, ReportWriter writer, Renderer renderer,
                                     LongSupplier clock) {
        return new ReportPipeline(settings, host, writer, renderer, clock);
    }

    /** False when configuration was broken at creation time and the pipeline is a no-op. */
    public boolean isActive() {
        return writer != null;
    }

    /**
     * The report file as an absolute path, for every line a human reads.
     *
     * <p>The configured value is normally relative ({@code errors-ai.log}), and it resolves
     * against the JVM's working directory — which is not something you can tell from a
     * console line. Printing the relative form leaves the reader hunting for a file whose
     * location the message was supposed to give them. Resolved once; a failure here falls
     * back to the configured value rather than costing anyone a report.
     */
    private String absoluteFile() {
        String resolved = absolutePath;
        if (resolved == null) {
            try {
                resolved = Path.of(settings.file()).toAbsolutePath().normalize().toString();
            } catch (RuntimeException e) {
                resolved = settings.file();
            }
            absolutePath = resolved;
        }
        return resolved;
    }

    public void process(LogEventData event) {
        try {
            if (writer == null || parked || SELF_LOGGER.equals(event.loggerName())
                    || REPORTS_LOGGER.equals(event.loggerName())) return;
            if (announced.compareAndSet(false, true)) {
                host.selfLog("stacktale active → " + absoluteFile()
                        + (settings.emitReportsToLogger() ? ""
                           : " (reports go to the file; set emitReportsToLogger=true to also see them here)"));
            }
            storyBuffer.record(event);
            if (!event.error()) return;

            // container echo: Tomcat/Spring re-log the exception the app just reported —
            // suppress the duplicate ONLY when this thread produced a report moments ago,
            // so apps that don't log before rethrowing still get their container report
            if (isContainerEcho(event)) return;

            Throwable throwable = event.throwable();
            if (throwable == null && !settings.reportErrorsWithoutThrowable()) return;

            DistilledStack stack = throwable == null ? null : distiller.distill(throwable);
            String fingerprint = stack != null
                    ? Fingerprinter.fingerprint(stack.rootType(), stack.culpritLine(), stack.rootMessage())
                    : Fingerprinter.fingerprint(event.loggerName(), "", event.messagePattern());

            Decision decision = deduper.decide(fingerprint);
            switch (decision.kind()) {
                case REPORT -> {
                    // storm control gates only full reports (summaries are already throttled);
                    // beyond the rate limit, distinct errors are counted, not dumped
                    StormLimiter.Outcome storm = stormLimiter.onReport();
                    if (storm.action() == StormLimiter.Action.SUPPRESS) {
                        deduper.rollback(fingerprint); // #51: report not written — re-arm a fresh one
                        return;
                    }
                    if (storm.action() == StormLimiter.Action.STORM_LINE) {
                        try {
                            writer.append(renderer.stormLine(storm.suppressed(), stormLimiter.maxPerWindow()));
                            stormLimiter.confirmStormLine(storm.suppressed()); // #57: clear only once written
                        } finally {
                            // #51: this error's own report was never written — and that is true
                            // whether or not the storm line itself made it. The rollback used to
                            // sit after the append, so a failure here skipped it and left
                            // reportPending=true; Deduper.decide then answers SILENT for this
                            // fingerprint until the dedup window rolls over, i.e. the error stops
                            // being reported for ~5 minutes. Unwind the decision either way.
                            deduper.rollback(fingerprint);
                        }
                        return;
                    }
                    String rendered;
                    try {
                        Map<String, String> fields = settings.captureExceptionFields()
                                ? FieldExtractor.extractChain(throwable)
                                : Map.of();
                        Report report = new Report(fingerprint, event.epochMillis(), event.threadName(),
                                stack, event.messagePattern(), event.args(), event.loggerName(),
                                event.mdc(), fields, AgentCaptures.forChain(throwable),
                                storyBuffer.storyFor(event), env.envLine(),
                                decision.totalOccurrences(), decision.firstSeenMillis(),
                                // resolved only when asked for: the seed costs a reflective
                                // call and carries argument values, so an app that has not
                                // opted in never materialises one
                                settings.repro() ? AgentCaptures.seedFor(throwable) : null);
                        rendered = renderer.render(report);
                        writer.append(rendered);
                    } catch (Throwable t) {
                        // the report was NOT durably written — don't leave the dedup window
                        // believing it exists; the next occurrence must get a fresh chance
                        deduper.rollback(fingerprint);
                        throw t;
                    }
                    // past this point the report is on disk: a failing shipper must not
                    // undo dedup state (that would duplicate the next occurrence's report)
                    deduper.confirmReport(fingerprint); // #51: clears the retry flag now it's written
                    String reportedOn = ThreadKey.of(event);
                    if (reportedOn != null) {
                        synchronized (lastReportByThread) {
                            lastReportByThread.put(reportedOn, System.currentTimeMillis());
                        }
                    }
                    host.selfLog("AI error report #" + fingerprint + " → " + absoluteFile());
                    if (settings.emitReportsToLogger()) host.emitReport(rendered);
                }
                case SUMMARY -> {
                    writer.append(renderer.renderSummary(fingerprint, decision.count(), decision.lastSeenMillis()));
                    // only now is the count durably on file; a failed append above throws
                    // to the outer catch and leaves it pending for close()'s drainPending()
                    deduper.confirmWritten(fingerprint, decision.count());
                }
                case SILENT -> { /* counted; nothing to write */ }
            }
            consecutiveFailures.set(0); // the write path is healthy again
        } catch (Throwable t) {
            int failures = consecutiveFailures.incrementAndGet();
            if (warnedOnce.compareAndSet(false, true)) {
                host.warn("stacktale failed to process an event", t);
            }
            if (failures >= MAX_CONSECUTIVE_FAILURES && !parked) {
                parked = true;
                host.warn("stacktale parked after " + failures + " consecutive failures writing "
                        + settings.file() + "; no further reports will be produced this run", t);
            }
        }
    }

    private boolean isContainerEcho(LogEventData event) {
        if (settings.echoSuppressionMillis() <= 0) return false;
        String logger = event.loggerName();
        boolean container = false;
        for (String prefix : settings.containerLoggers()) {
            if (logger.startsWith(prefix)) {
                container = true;
                break;
            }
        }
        if (!container) return false;
        // an unidentifiable thread cannot be matched to a report we just wrote; suppressing
        // on a shared key would drop another request's genuine error
        String reportedOn = ThreadKey.of(event);
        if (reportedOn == null) return false;
        Long last;
        synchronized (lastReportByThread) {
            last = lastReportByThread.get(reportedOn);
        }
        return last != null && System.currentTimeMillis() - last <= settings.echoSuppressionMillis();
    }

    /** Flushes pending repeat counters and storm-suppressed reports — call on shutdown. */
    public void close() {
        try {
            if (writer == null) return;
            for (Deduper.Pending pending : deduper.drainPending()) {
                writer.append(renderer.renderSummary(pending.fingerprint(), pending.count(), pending.lastSeenMillis()));
            }
            int suppressed = stormLimiter.drainSuppressed();
            if (suppressed > 0) {
                writer.append(renderer.stormLine(suppressed, stormLimiter.maxPerWindow()));
            }
        } catch (Throwable t) {
            // shutdown must never fail the host
        }
    }
}
