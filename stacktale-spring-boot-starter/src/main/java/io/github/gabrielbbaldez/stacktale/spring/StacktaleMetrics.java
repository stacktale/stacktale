package io.github.gabrielbbaldez.stacktale.spring;

import io.github.gabrielbbaldez.stacktale.ReportPipeline;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.function.ToDoubleFunction;

/**
 * Publishes stacktale's own counters as Micrometer meters (#96).
 *
 * <p>Not metrics <em>about</em> the application's errors — the reports are that. These are
 * about stacktale: is it running, is it still writing, and how much is it holding back. An
 * error reporter is the one component whose own failure is invisible by construction, because
 * the way it reports problems is the thing that broke.
 *
 * <p>{@code stacktale.parked} is the meter worth an alert. After repeated write failures the
 * pipeline stops producing for the rest of the run — deliberately, so a dead destination cannot
 * burn the application's CPU forever — and the only other trace is one warning at the moment it
 * happened, hours before anyone looks.
 *
 * <p>Registered only when Micrometer is on the classpath, so an application without it never
 * pays for this.
 */
class StacktaleMetrics implements MeterBinder {

    private final StacktaleAppender appender;

    StacktaleMetrics(StacktaleAppender appender) {
        this.appender = appender;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        counter(registry, "stacktale.reports", "Full st/ report blocks written",
                ReportPipeline.Stats::reportsWritten);
        counter(registry, "stacktale.summaries", "Repeat-count summary lines written",
                ReportPipeline.Stats::summariesWritten);
        counter(registry, "stacktale.suppressed.dedup",
                "Occurrences held back by the dedup window — errors that happened and were not written",
                ReportPipeline.Stats::dedupSuppressed);
        counter(registry, "stacktale.suppressed.storm",
                "Reports dropped by the per-minute rate limit",
                ReportPipeline.Stats::stormSuppressed);
        counter(registry, "stacktale.failures",
                "Throwables swallowed on the report path — the never-throw guarantee, counted",
                ReportPipeline.Stats::failures);
        counter(registry, "stacktale.rotations", "Times the report file rolled",
                ReportPipeline.Stats::rotations);

        // Two states rather than one meter with a tag: "configured badly at startup" and
        // "gave up after failing to write" are different incidents with different fixes.
        gauge(registry, "stacktale.active",
                "1 while the pipeline is configured and able to write, 0 when startup config was broken",
                stats -> stats.active() ? 1 : 0);
        gauge(registry, "stacktale.parked",
                "1 once the pipeline has stopped producing after repeated write failures — alert on this",
                stats -> stats.parked() ? 1 : 0);
    }

    private void counter(MeterRegistry registry, String name, String description,
                         ToDoubleFunction<ReportPipeline.Stats> value) {
        FunctionCounter.builder(name, appender, a -> read(a, value))
                .description(description)
                .register(registry);
    }

    private void gauge(MeterRegistry registry, String name, String description,
                       ToDoubleFunction<ReportPipeline.Stats> value) {
        Gauge.builder(name, appender, a -> read(a, value))
                .description(description)
                .register(registry);
    }

    /**
     * Reads one counter, tolerating an appender that has not started yet.
     *
     * <p>Meters are registered when the context comes up, which can be before the appender is
     * attached; {@code stats()} answers {@code null} until then. Zero is the honest reading for
     * "nothing has happened yet", and it keeps a scrape during startup from failing.
     */
    private static double read(StacktaleAppender appender, ToDoubleFunction<ReportPipeline.Stats> value) {
        ReportPipeline.Stats stats = appender.stats();
        return stats == null ? 0 : value.applyAsDouble(stats);
    }
}
