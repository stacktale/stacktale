package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one piece of stacktale state that outlives the process (#137).
 *
 * <p>Every test here opens a second store over the same file, because that is the whole feature:
 * an in-memory answer is what {@code seen:} already gives, and it is exactly the answer that
 * cannot say whether an error predates the deploy.
 */
class SeenStoreTest {

    private static final long DAY = 24 * 60 * 60 * 1000L;

    private final List<String> warnings = new ArrayList<>();

    private SeenStore open(Path dir, String build) {
        SeenStore store = SeenStore.open(dir.resolve("errors-ai.log"), build,
                (message, thrown) -> warnings.add(message));
        assertThat(store).isNotNull();
        store.noteBuild(1_000_000L);
        return store;
    }

    @Test
    void anErrorFirstSeenNowIsNewInThisBuild(@TempDir Path dir) {
        Provenance p = open(dir, "7e3c1f").record("a1b2c3d4", 1_000_000L);

        assertThat(p.newInThisBuild()).isTrue();
        assertThat(p.firstBuild()).isEqualTo("7e3c1f");
        assertThat(p.buildsAgo()).isZero();
    }

    /** The question the feature exists for: after a deploy, is this error mine or was it here? */
    @Test
    void anErrorSurvivingADeployIsNotNewAndSaysWhichBuildStartedIt(@TempDir Path dir) {
        open(dir, "9a2b1c").record("a1b2c3d4", 1_000_000L);

        // a new process, a new build, the same sidecar
        SeenStore afterDeploy = open(dir, "7e3c1f");
        Provenance p = afterDeploy.record("a1b2c3d4", 1_000_000L + DAY);

        assertThat(p.newInThisBuild()).isFalse();
        assertThat(p.firstBuild()).isEqualTo("9a2b1c");
        assertThat(p.firstSeenMillis()).isEqualTo(1_000_000L); // the original sighting, not today's
        assertThat(p.buildsAgo()).isEqualTo(1);

        // and an error genuinely introduced by this deploy still reads as new
        assertThat(afterDeploy.record("beefbeef", 1_000_000L + DAY).newInThisBuild()).isTrue();
    }

    @Test
    void buildsAgoCountsDeploysRatherThanRestarts(@TempDir Path dir) {
        open(dir, "b1").record("a1b2c3d4", 1_000_000L);
        open(dir, "b1"); // same build restarted: not a deploy
        open(dir, "b2");
        open(dir, "b3");

        assertThat(open(dir, "b3").record("a1b2c3d4", 2_000_000L).buildsAgo()).isEqualTo(2);
    }

    /**
     * A store that no longer holds the first build cannot count back to it — an evicted or
     * copied-in sidecar legitimately does not — and a made-up number is worse than none.
     */
    @Test
    void anUnknownFirstBuildReportsNoDistanceRatherThanGuessing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("errors-ai.log.seen"),
                "e a1b2c3d4 longgone 1000000\n", StandardCharsets.UTF_8);

        Provenance p = open(dir, "7e3c1f").record("a1b2c3d4", 2_000_000L);

        assertThat(p.newInThisBuild()).isFalse();
        assertThat(p.firstBuild()).isEqualTo("longgone");
        assertThat(p.buildsAgo()).isEqualTo(-1);
    }

    /** A kill mid-append leaves a torn line. Skipping it must not cost the rest of the file. */
    @Test
    void aTornLineIsSkippedAndTheRestOfTheFileSurvives(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("errors-ai.log.seen"), """
                b 9a2b1c 1000000
                e a1b2c3d4 9a2b1c 1000000
                e broken-half-writ
                e beefbeef 9a2b1c notanumber
                e cafecafe 9a2b1c 1000500
                """, StandardCharsets.UTF_8);

        SeenStore store = open(dir, "7e3c1f");

        assertThat(store.record("a1b2c3d4", 2_000_000L).firstBuild()).isEqualTo("9a2b1c");
        assertThat(store.record("cafecafe", 2_000_000L).firstBuild()).isEqualTo("9a2b1c");
        assertThat(store.record("beefbeef", 2_000_000L).newInThisBuild()).isTrue(); // the bad line was dropped
    }

    /**
     * Bounded like every other piece of state here — an unbounded sidecar is a slow leak. Loading
     * an oversized file is the cheap way to prove the cap: it exercises the same eviction the
     * append path uses, without two thousand file opens.
     */
    @Test
    void anOversizedSidecarIsCappedOnLoad(@TempDir Path dir) throws IOException {
        StringBuilder oversized = new StringBuilder("b 9a2b1c 1000000\n");
        for (int i = 0; i < 2_100; i++) {
            oversized.append("e ").append(String.format("%08x", i)).append(" 9a2b1c 1000000\n");
        }
        Files.writeString(dir.resolve("errors-ai.log.seen"), oversized.toString(), StandardCharsets.UTF_8);

        // a later build, so "still on file" and "new here" are distinguishable — under the same
        // build both read as new and the assertion would prove nothing
        SeenStore store = open(dir, "7e3c1f");

        assertThat(store.record(String.format("%08x", 0), 2_000_000L).newInThisBuild())
                .withFailMessage("the oldest id should have been evicted")
                .isTrue();
        assertThat(store.record(String.format("%08x", 2_099), 2_000_000L).newInThisBuild())
                .withFailMessage("the newest id should have been kept")
                .isFalse();
    }

    /**
     * Enrichment does not get to cost a report. An unwritable sidecar keeps answering from memory
     * for the rest of the run, and says so once — the failure is normally permanent, and a
     * warning per report would flood the log this is meant to keep readable.
     */
    @Test
    void anUnwritableSidecarWarnsOnceAndKeepsWorkingInMemory(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("errors-ai.log.seen")); // a directory: every write fails

        SeenStore store = open(dir, "7e3c1f");
        assertThat(store.record("a1b2c3d4", 1_000_000L).newInThisBuild()).isTrue();
        assertThat(store.record("beefbeef", 1_000_000L).newInThisBuild()).isTrue();
        // in-memory answers stay correct within the run
        assertThat(store.record("a1b2c3d4", 1_000_000L).firstSeenMillis()).isEqualTo(1_000_000L);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("provenance stays in memory");
    }

    /**
     * Without something to compare, every run looks like the same build and "NEW in this build"
     * would be a lie — so there is no store at all rather than a misleading one.
     */
    @Test
    void noBuildIdentityMeansNoProvenance(@TempDir Path dir) {
        assertThat(SeenStore.open(dir.resolve("errors-ai.log"), "", (m, t) -> { })).isNull();
        assertThat(SeenStore.open(dir.resolve("errors-ai.log"), null, (m, t) -> { })).isNull();
    }
}
