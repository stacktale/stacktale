package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    @Test
    void writesHeaderOnceAndAppends(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = new ReportWriter(file, 1024 * 1024, "# header\n");
        w.append("block-1\n");
        w.append("block-2\n");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("# header\nblock-1\nblock-2\n");
    }

    @Test
    void rotatesWhenMaxSizeExceeded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = new ReportWriter(file, 40, "# h\n");
        w.append("x".repeat(30) + "\n");
        w.append("y".repeat(30) + "\n"); // would exceed 40 → rotate first
        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8)).contains("x");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("# h\n").contains("y");
    }

    @Test
    void createsParentDirectories(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested/deep/errors-ai.log");
        new ReportWriter(file, 1024, "# h\n").append("b\n");
        assertThat(Files.exists(file)).isTrue();
    }
}
