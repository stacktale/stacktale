package io.github.gabrielbbaldez.stacktale;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Appends report blocks to the AI error log. New/empty files start with the
 * self-describing header; when the file would exceed {@code maxBytes} it is rotated to
 * {@code <name>.1} (one backup kept). Writes are UTF-8 and flushed per append — errors
 * are rare and readers (AI tools) may open the file immediately after.
 */
final class ReportWriter {

    private final Path file;
    private final long maxBytes;
    private final String header;

    ReportWriter(Path file, long maxBytes, String header) {
        this.file = file;
        this.maxBytes = maxBytes;
        this.header = header;
    }

    synchronized void append(String block) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            long size = Files.exists(file) ? Files.size(file) : 0;
            byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
            if (size > 0 && size + bytes.length > maxBytes) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                size = 0;
            }
            if (size == 0) {
                Files.writeString(file, header, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // the appender catches this and degrades
        }
    }
}
