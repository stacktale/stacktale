package io.github.gabrielbbaldez.stacktale.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the working tree around a report's culprit frame.
 *
 * <p>Deliberately not in {@code errors-ai.log}: that file is gitignored, redacted, uploaded to
 * CI artifacts, pasted into PR comments and sized for tokens, and source code belongs in none
 * of those. It is also <em>stale</em> — the log can be days old while the tree is current, and
 * during a fix loop only the current source is the right answer. Keeping code out of the file
 * and reading it here is the design, not a compromise (#138).
 */
final class Workspace {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code at PaymentService.charge(PaymentService.java:118) ← YOUR CODE} */
    private static final Pattern CULPRIT_LINE = Pattern.compile("^at (.+)$");
    /** {@code PaymentService.charge(PaymentService.java:118)} */
    private static final Pattern FRAME = Pattern.compile("^(\\S+)\\.(\\w+)\\(([^:()]+\\.\\w+):(\\d+)\\)");

    /** Build output and dependencies: a copy of the source there answers for the wrong tree. */
    private static final Set<String> SKIPPED = Set.of(
            "target", "build", "out", "bin", "node_modules", ".git", ".idea", ".gradle", ".mvn");
    private static final int MAX_DEPTH = 12;
    private static final int MAX_LINES_PER_FILE = 8;

    private final Path root;

    /**
     * @param root where the client launched the server — the same working directory the default
     *             {@code errors-ai.log} is resolved against, so "the app that wrote this log" and
     *             "the tree open in the editor" are normally one and the same
     */
    Workspace(Path root) {
        this.root = root;
    }

    /** A culprit frame, split into the parts both tools need. */
    record Frame(String className, String methodName, String fileName, int line) {
    }

    /**
     * The culprit frame of a report block, text or JSON, or {@code null} when it has none — a
     * report for an error logged without a throwable has no frame to point at.
     */
    static Frame culpritOf(String block) {
        if (block == null || block.isBlank()) {
            return null;
        }
        String frame = block.strip().startsWith("{") ? jsonFrame(block.strip()) : textFrame(block);
        if (frame == null) {
            return null;
        }
        Matcher m = FRAME.matcher(frame.strip());
        if (!m.find()) {
            return null;
        }
        return new Frame(m.group(1), m.group(2), m.group(3), Integer.parseInt(m.group(4)));
    }

    private static String jsonFrame(String block) {
        try {
            JsonNode frame = JSON.readTree(block).at("/error/culprit/frame");
            return frame.isMissingNode() ? null : frame.asText();
        } catch (IOException e) {
            return null;
        }
    }

    private static String textFrame(String block) {
        for (String line : block.split("\n", -1)) {
            Matcher m = CULPRIT_LINE.matcher(line.strip());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * The source around a frame, with line numbers and the culprit marked.
     *
     * <p>Every failure here is an answer rather than an error: a stack frame can name a file
     * that is not in this tree at all (a dependency, a generated class, a different service),
     * and a tool call that fails for that leaves the agent with nothing to act on.
     */
    String sourceAround(Frame frame, int radius) {
        List<Path> candidates = filesNamed(frame.fileName());
        if (candidates.isEmpty()) {
            return "No file named " + frame.fileName() + " under " + root.toAbsolutePath()
                    + ".\n\nThe frame may belong to a dependency, to generated code, or to another"
                    + " service whose reports share this log. get_report " + "still has the"
                    + " distilled stack and the story.";
        }
        Path file = candidates.get(0);
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Could not read " + display(file) + ": " + e.getMessage();
        }

        StringBuilder b = new StringBuilder();
        b.append(display(file)).append("  (").append(frame.className()).append('.')
                .append(frame.methodName()).append(", line ").append(frame.line()).append(")\n\n");
        if (frame.line() > lines.size()) {
            // the tree moved on since the report: saying so beats printing the end of the file
            // as if it were the failure site
            b.append("The report points at line ").append(frame.line()).append(", but the file has ")
                    .append(lines.size()).append(" lines — it has changed since the error was")
                    .append(" captured. Showing the last ").append(Math.min(radius, lines.size()))
                    .append(" lines instead.\n\n");
        }
        int culprit = Math.min(frame.line(), lines.size());
        int from = Math.max(1, culprit - radius);
        int to = Math.min(lines.size(), culprit + radius);
        int width = String.valueOf(to).length();
        for (int i = from; i <= to; i++) {
            b.append(String.format("%" + width + "d", i))
                    .append(i == frame.line() ? " > " : " | ")
                    .append(lines.get(i - 1)).append('\n');
        }
        if (candidates.size() > 1) {
            b.append("\n").append(candidates.size() - 1).append(" other file(s) share that name: ");
            b.append(String.join(", ", candidates.stream().skip(1).limit(5)
                    .map(this::display).toList()));
            b.append("\nThe distilled frame carries a simple class name, so pick by package if this"
                    + " is the wrong one.\n");
        }
        return b.toString();
    }

    /**
     * Test sources that mention the culprit's class and method.
     *
     * <p>A name match, not coverage: it is wrong in both directions — a test can exercise the
     * method through a caller and never name it, and naming it is not the same as covering the
     * failing path. The valuable answer is still the negative one. ORACLE-SWE ranks a
     * reproduction test above every other signal an agent can be handed, so "nothing names this
     * method" tells it to write one rather than spend turns looking for one that is not there.
     */
    String testsCovering(Frame frame) {
        List<Path> tests = testSources();
        if (tests.isEmpty()) {
            return "No test sources found under " + root.toAbsolutePath()
                    + " (looked for src/test/java and src/test/kotlin).";
        }
        StringBuilder hits = new StringBuilder();
        int matched = 0;
        for (Path test : tests) {
            String content;
            try {
                content = Files.readString(test, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue; // an unreadable or non-UTF-8 file is not worth failing the answer over
            }
            if (!content.contains(frame.className()) || !content.contains(frame.methodName())) {
                continue;
            }
            matched++;
            if (matched > 20) {
                continue;
            }
            hits.append("  ").append(display(test)).append('\n');
            String[] lines = content.split("\n", -1);
            int shown = 0;
            int naming = 0;
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].contains(frame.methodName())) {
                    continue;
                }
                naming++;
                // a method called in a loop of assertions produces dozens of identical lines, and
                // this answer is read by something paying per token: enough to open the file at
                // the right place, then the count
                if (shown++ < MAX_LINES_PER_FILE) {
                    hits.append("    ").append(i + 1).append(": ").append(lines[i].strip()).append('\n');
                }
            }
            if (naming > MAX_LINES_PER_FILE) {
                hits.append("    … ").append(naming - MAX_LINES_PER_FILE).append(" more line(s)\n");
            }
        }
        if (matched == 0) {
            return "none: no test source names " + frame.className() + "." + frame.methodName() + ".\n\n"
                    + "Searched " + tests.size() + " file(s) under src/test. This is a name match rather"
                    + " than coverage, so it can miss a test that reaches the method through a caller —"
                    + " but nothing naming it is a strong signal that the failing path is untested."
                    + " repro_for " + "gives you the call and its arguments to write one from.";
        }
        return matched + " test file(s) name " + frame.className() + "." + frame.methodName()
                + " (a name match, not coverage — a test can name the method without exercising"
                + " the failing path):\n\n" + hits;
    }

    /**
     * A repo-relative path with forward slashes on every platform. Whoever reads this answer is
     * as likely to quote the path back into a shell command as to open it, and a Windows
     * backslash in that position is an escape character.
     */
    private String display(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private List<Path> filesNamed(String fileName) {
        List<Path> found = walk(p -> p.getFileName().toString().equals(fileName));
        // prefer main sources over test copies, then the shallowest path — a fixture named after
        // a production class is a common way to answer with the wrong file
        found.sort(Comparator
                .comparing((Path p) -> p.toString().replace('\\', '/').contains("/src/test/"))
                .thenComparingInt(Path::getNameCount));
        return found;
    }

    private List<Path> testSources() {
        return walk(p -> {
            String path = p.toString().replace('\\', '/');
            return (path.contains("/src/test/java/") || path.contains("/src/test/kotlin/"))
                    && (path.endsWith(".java") || path.endsWith(".kt"));
        });
    }

    /** Bounded walk of the working tree: build output and VCS directories are never the answer. */
    private List<Path> walk(java.util.function.Predicate<Path> accept) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root, MAX_DEPTH)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(p))
                    .filter(accept)
                    .limit(500)
                    .forEach(found::add);
        } catch (IOException | RuntimeException e) {
            return found; // a partial answer beats a failed tool call
        }
        return found;
    }

    private boolean isSkipped(Path path) {
        for (Path segment : root.relativize(path)) {
            if (SKIPPED.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
