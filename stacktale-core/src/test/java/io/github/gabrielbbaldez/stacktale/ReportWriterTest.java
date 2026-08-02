package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    private static ReportWriter writer(Path file, long maxBytes, String header) {
        return new ReportWriter(file, maxBytes, header, null, false, 1);
    }

    @Test
    void writesHeaderOnceAndAppends(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = writer(file, 1024 * 1024, "# header\n");
        w.append("block-1\n");
        w.append("block-2\n");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("# header\nblock-1\nblock-2\n");
    }

    @Test
    void rotatesWhenMaxSizeExceeded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = writer(file, 40, "# h\n");
        w.append("x".repeat(30) + "\n");
        w.append("y".repeat(30) + "\n"); // would exceed 40 → rotate first
        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8)).contains("x");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("# h\n").contains("y");
    }

    @Test
    void createsParentDirectories(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested/deep/errors-ai.log");
        writer(file, 1024, "# h\n").append("b\n");
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void marksNewSessionWhenFileAlreadyHasContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        new ReportWriter(file, 1024 * 1024, "# h\n", "─── app start A ───\n", false, 1).append("block-1\n");
        // a new writer instance = a new application run
        new ReportWriter(file, 1024 * 1024, "# h\n", "─── app start B ───\n", false, 1).append("block-2\n");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // fresh file gets no marker; the restart does
        assertThat(content).isEqualTo("# h\nblock-1\n─── app start B ───\nblock-2\n");
    }

    @Test
    void truncateOnStartDropsThePreviousSession(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        new ReportWriter(file, 1024 * 1024, "# h\n", null, false, 1).append("old-session\n");
        new ReportWriter(file, 1024 * 1024, "# h\n", "─── app start ───\n", true, 1).append("block-new\n");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("# h\nblock-new\n");
    }

    @Test
    void keepsNRotatedBackups(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = new ReportWriter(file, 40, "# h\n", null, false, 3);
        w.append("a".repeat(30) + "\n");
        w.append("b".repeat(30) + "\n");
        w.append("c".repeat(30) + "\n");
        w.append("d".repeat(30) + "\n");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("d");
        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8)).contains("c");
        assertThat(Files.readString(dir.resolve("errors-ai.log.2"), StandardCharsets.UTF_8)).contains("b");
        assertThat(Files.readString(dir.resolve("errors-ai.log.3"), StandardCharsets.UTF_8)).contains("a");
        assertThat(Files.exists(dir.resolve("errors-ai.log.4"))).isFalse();
    }

    @Test
    void degradesToAppendingPastTheCapWhenRotationIsBlocked(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        List<String> warnings = new ArrayList<>();
        ReportWriter w = new ReportWriter(file, 40, "# h\n", null, false, 1, (m, t) -> warnings.add(m));
        w.append("a".repeat(30) + "\n");
        // stand in for a Windows reader holding the live file: an existing sibling makes the
        // rotation's rename fail, exactly as a locked handle would
        Files.createDirectory(dir.resolve("errors-ai.log.rotating"));

        w.append("b".repeat(30) + "\n"); // would exceed the cap → rotation blocked

        // nothing dropped, nothing wiped, no bogus backup, one warning
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("a").contains("b");
        assertThat(Files.exists(dir.resolve("errors-ai.log.1"))).isFalse();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("could not rotate");

        // the reader releases → the next report rotates normally, and the file recovers
        Files.delete(dir.resolve("errors-ai.log.rotating"));
        w.append("c".repeat(30) + "\n");
        assertThat(Files.exists(dir.resolve("errors-ai.log.1"))).isTrue();
        assertThat(warnings).hasSize(1); // recovery is silent, not a second warning
    }

    @Test
    void warnsOnlyOnceWhileRotationStaysBlocked(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        List<String> warnings = new ArrayList<>();
        ReportWriter w = new ReportWriter(file, 40, "# h\n", null, false, 1, (m, t) -> warnings.add(m));
        w.append("a".repeat(30) + "\n");
        Files.createDirectory(dir.resolve("errors-ai.log.rotating"));

        w.append("b".repeat(30) + "\n");
        w.append("c".repeat(30) + "\n");
        w.append("d".repeat(30) + "\n");

        assertThat(warnings).hasSize(1); // rate-limited: one warning for the whole episode
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("b").contains("c").contains("d");
    }

    @Test
    void zeroBackupsMeansRotationJustStartsFresh(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = new ReportWriter(file, 40, "# h\n", null, false, 0);
        w.append("a".repeat(30) + "\n");
        w.append("b".repeat(30) + "\n");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("# h\n").contains("b").doesNotContain("a");
        assertThat(Files.exists(dir.resolve("errors-ai.log.1"))).isFalse();
    }

    @Test
    void anOrphanRotatingFileIsFoldedInAndRotationWorksAgain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        // the state a SIGKILL between "move the live file aside" and "roll it into .1" leaves
        Files.writeString(dir.resolve("errors-ai.log.rotating"), "newest, written before the crash\n");
        Files.writeString(dir.resolve("errors-ai.log.1"), "older\n");

        List<String> warnings = new ArrayList<>();
        ReportWriter w = new ReportWriter(file, 40, "# h\n", null, false, 3, (m, t) -> warnings.add(m));

        // the orphan is gone, and its reports are reachable again as .1 — StReportFile.read()
        // scans <file> and <file>.1..N, so anywhere else they would be lost
        assertThat(Files.exists(dir.resolve("errors-ai.log.rotating"))).isFalse();
        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8))
                .contains("before the crash");
        assertThat(Files.readString(dir.resolve("errors-ai.log.2"), StandardCharsets.UTF_8))
                .contains("older");
        assertThat(warnings).isEmpty(); // finishing the job is not a problem worth reporting

        // and the thing the orphan used to break: rotation itself
        w.append("a".repeat(30) + "\n");
        w.append("b".repeat(30) + "\n");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("b").doesNotContain("a");
        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8)).contains("a");
    }

    @Test
    void anOrphanFromACrashAfterTheShiftDoesNotCostAnExtraBackupSlot(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        // crashed one step later: the shift already happened, so .1 is free and .2/.3 hold
        // what used to be .1/.2. Shifting again would push .3 off the end for no reason.
        Files.writeString(dir.resolve("errors-ai.log.rotating"), "newest\n");
        Files.writeString(dir.resolve("errors-ai.log.2"), "middle\n");
        Files.writeString(dir.resolve("errors-ai.log.3"), "oldest\n");

        new ReportWriter(file, 40, "# h\n", null, false, 3);

        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8)).contains("newest");
        assertThat(Files.readString(dir.resolve("errors-ai.log.2"), StandardCharsets.UTF_8)).contains("middle");
        assertThat(Files.readString(dir.resolve("errors-ai.log.3"), StandardCharsets.UTF_8)).contains("oldest");
    }

    @Test
    void anOrphanIsDiscardedWhenNoBackupsAreKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        Files.writeString(dir.resolve("errors-ai.log.rotating"), "nowhere to put this\n");

        new ReportWriter(file, 40, "# h\n", null, false, 0);

        // maxBackups=0 means rotation deletes rather than keeps; recovery matches that choice
        assertThat(Files.exists(dir.resolve("errors-ai.log.rotating"))).isFalse();
        assertThat(Files.exists(dir.resolve("errors-ai.log.1"))).isFalse();
    }
}
