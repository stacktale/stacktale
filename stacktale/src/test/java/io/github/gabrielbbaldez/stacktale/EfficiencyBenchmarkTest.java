package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What it costs an AI to read one production failure, and whether it can answer at all.
 *
 * <p>The README quotes savings that were measured once, by hand. This regenerates them from
 * a scenario the build actually runs, and adds the dimension a size comparison misses: a
 * stack trace is cheap and does not contain the answer, a log tail contains the answer and
 * is enormous. The report is the only artifact that is both small and sufficient.
 *
 * <p>The scenario is one failing checkout in a service that is also serving other traffic —
 * the reason the explaining line is not next to the stack trace in a real log.
 *
 * <p>Token counts are the usual {@code chars / 4} approximation, stated as such. The point
 * is the ratio between artifacts measured the same way, not an exact bill.
 */
class EfficiencyBenchmarkTest {

    /** Facts an assistant needs to fix this without asking a follow-up question. */
    private static final Map<String, String> FACTS = new LinkedHashMap<>();

    static {
        FACTS.put("root cause", "NullPointerException");
        FACTS.put("our class appears at all", "CheckoutService");
        FACTS.put("why it was null (the cache miss)", "cache miss for customer 555");
        FACTS.put("the values involved", "889");
        FACTS.put("environment (java version)", "java ");
    }

    private LoggerContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop();
    }

    @Test
    void theReportIsSmallerThanAStackTraceAndStillAnswersTheQuestion(@TempDir Path dir) throws Exception {
        Path classicLog = dir.resolve("app.log");
        Path reportLog = dir.resolve("errors-ai.log");
        Throwable thrown = runScenario(classicLog, reportLog);

        String classicAll = Files.readString(classicLog);
        String stackTraceOnly = stackTraceOf(thrown);
        String tail = lastLines(classicAll, 200);
        String report = onlyTheReport(Files.readString(reportLog));

        List<Artifact> artifacts = List.of(
                new Artifact("Stack trace alone (what gets pasted)", stackTraceOnly),
                new Artifact("Stack trace + 200 lines of log tail", stackTraceOnly + "\n" + tail),
                new Artifact("Whole app.log for the session", classicAll),
                new Artifact("stacktale report (st/1)", report));

        String table = table(artifacts);
        System.out.println(table);
        // a build artifact, so the README's numbers can be regenerated rather than recalled
        Path out = Path.of("target", "efficiency.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table);

        Artifact pasted = artifacts.get(0);
        Artifact withTail = artifacts.get(1);
        Artifact whole = artifacts.get(2);
        Artifact st = artifacts.get(3);

        // 1. the artifact people actually paste does not contain the answer
        assertThat(facts(pasted.content())).containsEntry("why it was null (the cache miss)", false);

        // 2. neither does the usual remedy. Concurrent traffic pushed the explaining line
        //    out of the window, so paying 200 more lines buys no new fact — this is the
        //    interrogation loop, reproduced.
        assertThat(facts(withTail.content())).containsEntry("why it was null (the cache miss)", false);

        // 3. the whole log does contain it, at a multiple of the cost
        assertThat(facts(whole.content())).containsEntry("why it was null (the cache miss)", true);
        assertThat(tokens(whole.content())).isGreaterThan(5 * tokens(st.content()));

        // 4. the report carries every fact...
        assertThat(facts(st.content())).allSatisfy((label, found) ->
                assertThat(found).describedAs("report is missing: %s", label).isTrue());
        // 5. ...while costing less than the bare stack trace people paste today
        assertThat(tokens(st.content())).isLessThan(tokens(pasted.content()));
    }

    /**
     * One failing checkout while three other requests are in flight, logged through a
     * conventional file appender and stacktale at the same time — same events, two readers.
     */
    private Throwable runScenario(Path classicLog, Path reportLog) throws Exception {
        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(ctx);
        encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();
        FileAppender<ILoggingEvent> classic = new FileAppender<>();
        classic.setContext(ctx);
        classic.setFile(classicLog.toString());
        classic.setEncoder(encoder);
        classic.start();

        StacktaleAppender stacktale = new StacktaleAppender();
        stacktale.setContext(ctx);
        stacktale.setFile(reportLog.toString());
        // the packages the culprit lives in — here, this test's own
        stacktale.setAppPackages("io.github.gabrielbbaldez.stacktale");
        stacktale.setInstallUncaughtHandler(false);
        stacktale.start();

        ch.qos.logback.classic.Logger root = ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.addAppender(classic);
        root.addAppender(stacktale);
        root.setLevel(Level.INFO);

        Logger controller = ctx.getLogger("com.acme.OrderController");
        Logger client = ctx.getLogger("com.acme.CustomerClient");
        Logger cache = ctx.getLogger("com.acme.CustomerCache");
        Logger service = ctx.getLogger("com.acme.CheckoutService");

        // this request's own trail — the three lines that explain the failure
        controller.info("POST /orders/{}/confirm", 889);
        client.info("fetching customer {} → HTTP 404", 555);
        cache.warn("cache miss for customer {}, returning null", 555);

        // Other requests, served while this one is still in flight. They run on their own
        // threads (so stacktale keeps them out of this request's story, as it would in
        // production) and are joined before the error is logged, so the distance between
        // the cache-miss line and the stack trace is a fixed 300 lines rather than a
        // scheduling accident — the measurement has to mean the same thing on every machine.
        Thread[] traffic = new Thread[3];
        for (int i = 0; i < traffic.length; i++) {
            final int id = i;
            traffic[i] = new Thread(() -> {
                Logger log = ctx.getLogger("com.acme.OrderController");
                for (int n = 0; n < 100; n++) {
                    log.info("GET /orders/{} → 200 in {}ms", 1000 + id * 100 + n, 12 + n % 7);
                }
            }, "http-nio-8080-exec-" + (i + 2));
            traffic[i].start();
        }
        for (Thread t : traffic) t.join();

        Throwable thrown;
        try {
            throw failingCheckout(889L);
        } catch (RuntimeException e) {
            thrown = e;
            service.error("Failed to confirm order {}", 889, e);
        }

        stacktale.stop();
        classic.stop();
        return thrown;
    }

    private RuntimeException failingCheckout(long orderId) {
        try {
            CheckoutService.confirm(orderId, null); // the cache returned null
            throw new IllegalStateException("unreachable");
        } catch (NullPointerException npe) {
            return new RuntimeException("confirmation aborted for order " + orderId, npe);
        }
    }

    /** Stands in for the application class that owns the culprit frame. */
    static class CheckoutService {
        static String confirm(long orderId, String customerEmail) {
            return customerEmail.trim() + orderId; // NPE — this frame is the culprit
        }
    }

    private String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private String lastLines(String text, int n) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - n);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    /** The report block without the file's self-describing header. */
    private String onlyTheReport(String file) {
        int start = file.indexOf("━━━ ERROR #");
        return start < 0 ? file : file.substring(start);
    }

    private Map<String, Boolean> facts(String artifact) {
        Map<String, Boolean> present = new LinkedHashMap<>();
        FACTS.forEach((label, needle) -> present.put(label, artifact.contains(needle)));
        return present;
    }

    /** The customary chars/4 approximation — comparable across artifacts, not a bill. */
    private int tokens(String s) {
        return Math.round(s.length() / 4f);
    }

    private String table(List<Artifact> artifacts) {
        StringBuilder sb = new StringBuilder("\n| What the AI reads | Lines | ≈ Tokens | Answers? |\n");
        sb.append("|---|---:|---:|---|\n");
        for (Artifact a : artifacts) {
            Map<String, Boolean> f = facts(a.content());
            long have = f.values().stream().filter(Boolean::booleanValue).count();
            sb.append(String.format("| %s | %d | %d | %d/%d facts |%n",
                    a.name(), a.content().split("\n").length, tokens(a.content()), have, f.size()));
        }
        return sb.toString();
    }

    private record Artifact(String name, String content) {
    }
}
