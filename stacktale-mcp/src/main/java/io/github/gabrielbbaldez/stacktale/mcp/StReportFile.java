package io.github.gabrielbbaldez.stacktale.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses st/1 report files (the main file plus rotated {@code .N} backups) into report
 * entries an MCP tool can serve. Read-only; re-reads on every call — the files are small
 * by design and freshness beats caching for a debugging tool.
 */
final class StReportFile {

    record StReport(String id, String timestamp, String headline, int repeats, String block) {}

    private static final Pattern HEADER = Pattern.compile(
            "^━━━ ERROR #(\\w+) ━━━ (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ");
    private static final Pattern REPEAT = Pattern.compile("^━ #(\\w+) repeated (\\d+)× ");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    StReportFile(Path file) {
        this.file = file;
    }

    List<StReport> read() throws IOException {
        Map<String, StReport> byId = new LinkedHashMap<>();
        // oldest backup first so the main file's newer data wins ordering naturally
        List<Path> sources = new ArrayList<>();
        // scan contiguous .N backups (ReportWriter's maxBackups is unbounded); stop at the
        // first gap so a huge configured retention doesn't mean scanning thousands of paths
        List<Path> backups = new ArrayList<>();
        for (int i = 1; ; i++) {
            Path backup = file.resolveSibling(file.getFileName() + "." + i);
            if (!Files.exists(backup)) break;
            backups.add(backup);
        }
        for (int i = backups.size() - 1; i >= 0; i--) sources.add(backups.get(i)); // oldest first
        if (Files.exists(file)) sources.add(file);

        for (Path source : sources) {
            parseSource(Files.readString(source, StandardCharsets.UTF_8), byId);
        }
        return new ArrayList<>(byId.values());
    }

    /** Text st/1 or NDJSON st-json/1 — decided by the first non-blank line ('{' = JSON). */
    private void parseSource(String content, Map<String, StReport> byId) {
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("{")) parseJson(content, byId);
            else parse(content, byId);
            return;
        }
    }

    private void parse(String content, Map<String, StReport> byId) {
        String[] lines = content.split("\n", -1);
        StringBuilder block = null;
        String id = null;
        String timestamp = null;
        String headline = null;
        for (String line : lines) {
            Matcher header = HEADER.matcher(line);
            if (header.find()) {
                block = new StringBuilder(line).append('\n');
                id = header.group(1);
                timestamp = header.group(2);
                headline = null;
                continue;
            }
            if (block != null) {
                block.append(line).append('\n');
                if (headline == null && !line.isBlank()) headline = line;
                if (line.startsWith("━━━ END #")) {
                    // the same fingerprint can produce a second full block after the dedup
                    // window expires — carry forward the earlier count and keep the earliest
                    // timestamp instead of silently resetting recurrence to 1
                    StReport prior = byId.get(id);
                    int repeats = prior == null ? 1 : Math.max(prior.repeats(), 1);
                    String firstTs = prior == null ? timestamp : prior.timestamp();
                    byId.put(id, new StReport(id, firstTs, headline, repeats, block.toString()));
                    block = null;
                }
                continue;
            }
            Matcher repeat = REPEAT.matcher(line);
            if (repeat.find()) {
                StReport existing = byId.get(repeat.group(1));
                if (existing != null) {
                    byId.put(existing.id(), new StReport(existing.id(), existing.timestamp(),
                            existing.headline(), Integer.parseInt(repeat.group(2)), existing.block()));
                }
            }
        }
    }

    /** Parses the st-json/1 NDJSON variant into the same {@link StReport} records as the text format. */
    private void parseJson(String content, Map<String, StReport> byId) {
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue;
            JsonNode node;
            try {
                node = JSON.readTree(trimmed);
            } catch (IOException e) {
                continue; // a torn/half-written line must not abort the rest (FORMAT.md §7)
            }
            String type = node.path("type").asText();
            if ("report".equals(type)) {
                String id = node.path("id").asText();
                if (id.isEmpty()) continue;
                int count = Math.max(node.path("recurrence").path("count").asInt(1), 1);
                StReport prior = byId.get(id);
                int repeats = prior == null ? count : Math.max(prior.repeats(), count);
                String firstTs = prior == null ? jsonTimestamp(node.path("ts").asText()) : prior.timestamp();
                byId.put(id, new StReport(id, firstTs, jsonHeadline(node.path("error")), repeats, pretty(node)));
            } else if ("repeat".equals(type)) {
                StReport existing = byId.get(node.path("id").asText());
                if (existing != null) {
                    int count = Math.max(node.path("count").asInt(existing.repeats()), existing.repeats());
                    byId.put(existing.id(), new StReport(existing.id(), existing.timestamp(),
                            existing.headline(), count, existing.block()));
                }
            }
            // header / session / storm carry nothing to serve
        }
    }

    /** The headline the text format would print for a JSON report's error object. */
    private static String jsonHeadline(JsonNode error) {
        if (error.path("noException").asBoolean(false)) {
            return "ERROR (no exception): " + error.path("message").asText("");
        }
        String type = error.path("type").asText("");
        String message = error.path("message").asText("");
        return message.isEmpty() ? type : type + ": " + message;
    }

    /** ISO-8601 (e.g. 2026-07-10T20:16:40.412Z) → the text format's yyyy-MM-dd HH:mm:ss.SSS. */
    private static String jsonTimestamp(String iso) {
        try {
            return OffsetDateTime.parse(iso).toLocalDateTime().format(TS);
        } catch (RuntimeException e) {
            return iso; // keep whatever is there rather than dropping the report
        }
    }

    private static String pretty(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
        } catch (Exception e) {
            return node.toString() + "\n";
        }
    }

    static LocalDateTime parseTimestamp(String ts) {
        return LocalDateTime.parse(ts, TS);
    }
}
