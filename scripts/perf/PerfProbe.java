import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Times the two paths the "negligible overhead" claim is about, and prints one line per result.
 *
 * <p>Driven by {@code scripts/check-perf.sh}, which runs this against two builds of stacktale —
 * the pull request's and its merge base — alternating between them. That is the whole design: a
 * benchmark number is only comparable to another taken within seconds of it, and this program
 * exists to be run twice in a row rather than to produce an absolute figure.
 *
 * <p>Deliberately not JMH. JMH's rigour is aimed at getting one number right; the problem here is
 * drift between two numbers, which more forking and warmup does not fix. What fixes it is
 * measuring both arms in the same few seconds, which is the driver's job.
 *
 * <p>Only SLF4J and the appender's class name are used, and both are stable across every version
 * this could run against. A probe calling stacktale's own API would stop compiling against an
 * older base the first time a signature changed, and the driver would report that as a
 * regression.
 */
public final class PerfProbe {

    /**
     * Sized so a round takes a few hundred milliseconds on either path.
     *
     * <p>Calibrated against a null — the same commit on both arms, where the true ratio is 1.0.
     * At 400k/20k a round took tens of milliseconds and the null came back at 1.188 and 0.931:
     * ±19% of noise on a question whose answer is zero, which would have made the guard fire on
     * a documentation change. Longer rounds are the cheapest way to buy that back, because
     * scheduling noise is roughly constant per round rather than per iteration.
     */
    private static final int INFO_ITERATIONS = 2_000_000;
    private static final int ERROR_ITERATIONS = 100_000;
    private static final int ROUNDS = 5;

    private static int sink;

    public static void main(String[] args) throws Exception {
        Logger log = LoggerFactory.getLogger("com.acme.PerfProbe");
        RuntimeException recurring = new IllegalStateException("gateway timeout");
        recurring.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.PaymentService", "charge", "PaymentService.java", 118),
        });

        // The happy path: a non-error event. It reaches the story buffer and returns, which is
        // where "stacktale costs nothing until something breaks" has to hold.
        report("info", INFO_ITERATIONS, () -> log.info("charging card for order {}", 889));

        // The error path, deduplicated: one full report, then repeats. A distinct error every
        // time would measure the filesystem instead of stacktale.
        report("error", ERROR_ITERATIONS, () -> log.error("charge failed for order {}", 889, recurring));

        assertStacktaleActuallyRan();
    }

    /**
     * The failure this guard would otherwise never notice.
     *
     * <p>A misconfigured classpath, an unreadable file path, a Logback config that was not found:
     * every one of them ends with stacktale degrading to a no-op — by design, since it must never
     * break the host — and the probe happily timing a logger that does nothing. Numbers would
     * still be produced, the ratio would still be ~1.0, and the guard would pass forever while
     * measuring nothing. It cost an hour to find the first time, on a run whose only symptom was
     * being slow.
     */
    private static void assertStacktaleActuallyRan() throws Exception {
        String file = System.getProperty("stacktale.perf.file");
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("stacktale.perf.file is not set; cannot verify the appender ran");
        }
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            throw new IllegalStateException("stacktale wrote no report to " + path.toAbsolutePath()
                    + " — the appender is not attached, and these timings measure nothing");
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (!content.contains("ERROR #")) {
            throw new IllegalStateException("no report block in " + path.toAbsolutePath()
                    + " — the appender started but produced nothing");
        }
    }

    private static void report(String name, int iterations, Runnable op) {
        for (int i = 0; i < iterations / 2; i++) {
            op.run();
        }
        long[] rounds = new long[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                op.run();
            }
            rounds[round] = (System.nanoTime() - start) / iterations;
        }
        java.util.Arrays.sort(rounds);
        // The median, not the mean: one descheduled round should not move the answer, which is
        // the same reason the driver compares medians across arms.
        System.out.println(name + "=" + rounds[ROUNDS / 2]);
        sink += rounds[0];
    }
}
