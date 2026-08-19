package io.github.gabrielbbaldez.stacktale.quarkus.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

/**
 * {@code stacktale.*} configuration, read from {@code application.properties} (or any other
 * Quarkus config source) at run time — so the same native image can be reconfigured via
 * environment variables without a rebuild. Mirrors the settings the other stacktale adapters
 * expose, so a Quarkus app configures stacktale like a Spring Boot one; only the syntax differs.
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "stacktale")
public interface StacktaleConfig {

    /** Master switch. When {@code false}, the extension attaches nothing and stays a no-op. */
    @WithDefault("true")
    boolean enabled();

    /** File the AI-ready reports are written to. */
    @WithDefault("errors-ai.log")
    String file();

    /**
     * Comma-separated package prefixes marking "YOUR CODE" in distilled stacks. When empty, the
     * extension deduces it from the application's root package at build time.
     */
    Optional<String> appPackages();

    /** Number of preceding log events kept as the story leading up to an error. */
    @WithDefault("15")
    int storySize();

    /** How far back (seconds) the story may reach. */
    @WithDefault("60")
    int storyWindowSeconds();

    /** One full report per error fingerprint per window (seconds); repeats are counted, not re-emitted. */
    @WithDefault("300")
    int dedupWindowSeconds();

    /** Truncate the report file on every application start. */
    @WithDefault("false")
    boolean truncateOnStart();

    /** Route uncaught exceptions (threads dying without a log call) back into stacktale. */
    @WithDefault("true")
    boolean installUncaughtHandler();

    /** log.error(...) without an exception still produces a report. */
    @WithDefault("true")
    boolean reportErrorsWithoutThrowable();

    /** Read state from the root-cause exception's getters into a fields: section. */
    @WithDefault("true")
    boolean captureExceptionFields();

    /** Redact secrets (tokens, passwords, emails, …) from reports. */
    @WithDefault("true")
    boolean redactionEnabled();

    /** Extra redaction regexes applied on top of the built-ins. */
    Optional<List<String>> redactPatterns();

    /** Append a stable keyed-hash token to masked values so repeated secrets can be correlated. */
    @WithDefault("false")
    boolean redactionCorrelation();

    /** MDC keys that group the story per request when a JUL bridge supplies MDC-like values. */
    @WithDefault("traceId,trace_id,correlationId,requestId")
    String correlationMdcKeys();

    /** Timezone for report timestamps; empty means system default. */
    Optional<String> zone();

    /** Log one line per HTTP request into the story. */
    @WithDefault("true")
    boolean requestLogging();

    /** Suppress container re-logs of a failure this thread just reported; {@code 0} disables it. */
    @WithDefault("2000")
    long echoSuppressionMillis();

    /** Extra logger prefixes treated as container echoes, added to the defaults. */
    Optional<List<String>> containerLoggers();

    /** Also emit each report block as one event via logger {@code stacktale.reports}. */
    @WithDefault("false")
    boolean emitReportsToLogger();

    /** Size-based rotation threshold, MB. */
    @WithDefault("5")
    int maxFileSizeMb();

    /** Rotated backups kept; {@code 0} means start fresh on rotation. */
    @WithDefault("1")
    int maxBackups();

    /** Cap on reports per minute to survive error floods; {@code 0} means unlimited. */
    @WithDefault("0")
    int maxReportsPerMinute();

    /** Report format: {@code text} (human/AI-readable) or {@code json} (st-json/1 NDJSON). */
    @WithDefault("text")
    String format();
}
