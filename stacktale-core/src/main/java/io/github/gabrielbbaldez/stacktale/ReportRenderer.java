package io.github.gabrielbbaldez.stacktale;

import org.slf4j.helpers.MessageFormatter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeMap;

/**
 * Pure formatter for the st/1 report format. The output of this class is a public,
 * versioned API — golden-file tests pin it, and changes mean a format version bump.
 */
final class ReportRenderer implements Renderer {

    static final String FORMAT_VERSION = "st/1";

    private static final int MAX_ARGS = 8;
    private static final int MAX_ARG_LENGTH = 80;
    private static final int MAX_LOGGER_PAD = 20;

    private final DateTimeFormatter dateTime;
    private final DateTimeFormatter time;
    /** Provenance spans deploys, so its dates are days old; a millisecond there is noise. */
    private final DateTimeFormatter date;
    private final Redactor redactor;

    ReportRenderer(ZoneId zone) {
        this(zone, Redactor.withDefaults(List.of()));
    }

    ReportRenderer(ZoneId zone, Redactor redactor) {
        this.dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone);
        this.time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone);
        this.date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone);
        this.redactor = redactor;
    }

    /** All user-controlled content goes through here: newline flattening + redaction. */
    private String clean(String s) {
        return redactor.redact(flat(s));
    }

    @Override
    public String render(Report r) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("━━━ ERROR #").append(r.id()).append(" ━━━ ")
                .append(dateTime.format(Instant.ofEpochMilli(r.epochMillis())))
                .append(" thread=").append(r.threadName()).append(" ━━━\n");

        DistilledStack stack = r.stack();
        if (stack != null) {
            sb.append(stack.rootType());
            if (stack.rootMessage() != null && !stack.rootMessage().isBlank()) {
                sb.append(": ").append(clean(stack.rootMessage()));
            }
            sb.append('\n');
            if (stack.culpritLine() != null) {
                sb.append("at ").append(stack.culpritLine());
                if (stack.culpritIsAppCode()) sb.append(" ← YOUR CODE");
                sb.append('\n');
            }
            for (String w : stack.wrappedBy()) {
                sb.append("wrapped by: ").append(clean(w)).append('\n');
            }
        } else {
            // same cap as a root cause message: the formatted result can carry an argument
            // of any size, and this is the headline of the whole block
            sb.append("ERROR (no exception): ")
                    .append(clean(StackDistiller.capRootMessage(
                            MessageFormatter.arrayFormat(r.messagePattern(), r.args()).getMessage())))
                    .append('\n');
        }

        sb.append("log: \"").append(clean(r.messagePattern())).append('"');
        String args = renderArgs(r.messagePattern(), r.args());
        if (!args.isEmpty()) sb.append(" args=[").append(args).append(']');
        sb.append(" logger=").append(abbreviate(r.loggerName())).append('\n');

        // key and value are cleaned as one string: name-based redaction ("password=…")
        // structurally cannot fire on a value cleaned in isolation
        if (r.mdc() != null && !r.mdc().isEmpty()) {
            sb.append("mdc:");
            new TreeMap<>(r.mdc()).forEach((k, v) -> sb.append(' ').append(clean(k + "=" + v)));
            sb.append('\n');
        }

        if (r.fields() != null && !r.fields().isEmpty()) {
            sb.append("fields:");
            new TreeMap<>(r.fields()).forEach((k, v) -> sb.append(' ').append(clean(k + "=" + v)));
            sb.append('\n');
        }

        if (r.captured() != null && !r.captured().isEmpty()) {
            sb.append("captured (method args at throw site, via stacktale-agent):\n");
            for (String line : r.captured()) sb.append("  ").append(clean(line)).append('\n');
        }

        renderRepro(sb, r);

        // recurrence: only shown when the error is not brand new — "is this systemic or a
        // one-off?" changes the reader's urgency, and the answer was previously invisible
        // inside a fresh report (a window had expired since the last one)
        if (r.occurrences() > 1) {
            sb.append("seen: ").append(r.occurrences()).append("× this session, first at ")
                    .append(time.format(Instant.ofEpochMilli(r.firstSeenMillis()))).append('\n');
        }

        renderFirstSeen(sb, r);

        renderStory(sb, r);

        if (stack != null) {
            sb.append('\n');
            sb.append("stack (distilled, ").append(stack.shownFrames()).append(" of ")
                    .append(stack.totalFrames()).append(" frames):\n");
            for (String line : stack.frameLines()) sb.append("  ").append(line).append('\n');
            for (String line : stack.suppressed()) sb.append("  ").append(clean(line)).append('\n');
        }

        sb.append('\n');
        sb.append("env: ").append(r.envLine()).append('\n');
        sb.append("━━━ END #").append(r.id()).append(" ━━━\n");
        return sb.toString();
    }

    @Override
    public String renderSummary(String id, int count, long lastMillis) {
        return "━ #" + id + " repeated " + count + "× (last "
                + time.format(Instant.ofEpochMilli(lastMillis)) + ") ━\n";
    }

    @Override
    public String fileHeader() {
        return """
                # AI-oriented error reports (format st/1, https://github.com/stacktale/stacktale)
                # Each report is delimited by "━━━ ERROR #<id> ━━━" ... "━━━ END #<id> ━━━".
                # Sections: headline (root cause first), at (culprit frame), log, mdc,
                # fields (state carried by the exception's own getters/fields),
                # repro (opt-in: the throw site's typed signature and argument values),
                # story (events leading up to and including the error, oldest first),
                # stack (distilled; framework frames collapsed), env. "← YOUR CODE" marks app frames.
                # Repeated errors append "━ #<id> repeated N× ━" lines instead of new reports.
                # "─── app start … ───" lines mark application restarts.
                # "━ storm: N report(s) suppressed ━" lines mark rate-limited error floods.
                """;
    }

    @Override
    public String sessionMarker(long epochMillis, long pid) {
        return "─── app start " + dateTime.format(Instant.ofEpochMilli(epochMillis))
                + " (pid " + pid + ") ───\n";
    }

    @Override
    public String stormLine(int suppressed, int limit) {
        return "━ storm: " + suppressed + " report(s) suppressed (rate limit " + limit + "/min) ━\n";
    }

    /**
     * The reproduction seed: the call that threw, typed, with the values it was given.
     *
     * <p>Off unless {@code repro} is switched on, because this is the only section that
     * renders argument <em>values</em> against a named signature — a bigger privacy surface
     * than anything else in a report, and the reason it is not a default. Values still go
     * through redaction on the way out, like every other rendered value.
     *
     * <p>Rendered so an agent can write the test from it rather than run it unmodified. The
     * declared types are what make the signature reconstructable; the values are the inputs;
     * the {@code throws} line is the assertion.
     */
    private void renderRepro(StringBuilder sb, Report r) {
        ReproSeed seed = r.repro();
        if (seed == null) return;

        sb.append("repro (throw site, via stacktale-agent):\n");
        sb.append("  ").append(clean(seed.className())).append('#').append(clean(seed.methodName()))
                .append('(');
        for (int i = 0; i < seed.params().size(); i++) {
            ReproSeed.Param p = seed.params().get(i);
            if (i > 0) sb.append(", ");
            sb.append(clean(p.type())).append(' ').append(clean(p.name()));
        }
        sb.append(")\n");
        for (ReproSeed.Param p : seed.params()) {
            // name and value cleaned as one string, for the same reason mdc: and fields: are:
            // name-based redaction keys off "password=…", and a value cleaned in isolation has
            // no name in front of it for the rule to match
            sb.append("    ").append(clean(p.name() + " = " + p.value())).append('\n');
        }
        // the expected outcome, so the seed carries its own assertion rather than making the
        // reader scroll back to the headline
        if (r.stack() != null && r.stack().rootType() != null) {
            sb.append("  throws ").append(clean(r.stack().rootType()));
            String message = r.stack().rootMessage();
            if (message != null && !message.isBlank()) sb.append(": ").append(clean(message));
            sb.append('\n');
        }
    }

    /**
     * Provenance: whether this error predates the build now running (#137).
     *
     * <p>Sits next to {@code seen:} because both answer "how new is this", on different clocks —
     * {@code seen:} counts this session, this counts deploys. Only the first resets when the
     * process restarts, which is exactly why it could never settle the question a triage opens
     * with.
     */
    private void renderFirstSeen(StringBuilder sb, Report r) {
        Provenance p = r.provenance();
        if (p == null) return;

        sb.append("first seen: ");
        if (p.newInThisBuild()) {
            // the point of the whole feature, phrased so it can be grepped for
            sb.append("NEW in this build (").append(clean(p.firstBuild())).append(')');
        } else {
            sb.append("build ").append(clean(p.firstBuild()));
            // -1 means the store no longer holds that build; a count would be invented
            if (p.buildsAgo() > 0) {
                sb.append(", ").append(p.buildsAgo())
                        .append(p.buildsAgo() == 1 ? " build ago" : " builds ago");
            }
            sb.append(" (").append(date.format(Instant.ofEpochMilli(p.firstSeenMillis()))).append(')');
        }
        sb.append('\n');
    }

    private void renderStory(StringBuilder sb, Report r) {
        List<StoryEntry> entries = r.story() == null ? List.of() : r.story().entries();
        if (entries.isEmpty()) return;

        sb.append('\n');
        long span = entries.get(entries.size() - 1).epochMillis() - entries.get(0).epochMillis();
        sb.append("story (").append(r.story().contextLabel()).append(", last ").append(entries.size())
                .append(entries.size() == 1 ? " event, " : " events, ").append(span).append("ms):\n");
        // tell the reader context was cut by age, not simply never logged — decisive for
        // batch jobs / consumers whose opening line can be older than the story window
        if (r.story().omittedByAge() > 0) {
            sb.append("  … ").append(r.story().omittedByAge())
                    .append(" earlier event(s) older than the story window omitted\n");
        }

        int loggerPad = Math.min(MAX_LOGGER_PAD,
                entries.stream().mapToInt(e -> e.logger().length()).max().orElse(0));

        int errorIdx = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).epochMillis() == r.epochMillis()) {
                errorIdx = i;
                break;
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            StoryEntry e = entries.get(i);
            sb.append("  ").append(time.format(Instant.ofEpochMilli(e.epochMillis())))
                    .append(' ').append(pad(e.level(), 5))
                    .append(' ').append(pad(e.logger(), loggerPad))
                    .append("  ").append(clean(e.message()));
            if (i == errorIdx) sb.append("   ← this error");
            sb.append('\n');
        }
    }

    private String renderArgs(String pattern, Object[] args) {
        if (args == null || args.length == 0) return "";
        // "password={}" puts the secret in the ARG, where name-based redaction can't see
        // it — the pattern tells us which arg positions hold secrets
        java.util.Set<Integer> secretIndexes = redactor.secretArgIndexes(pattern);
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(args.length, MAX_ARGS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            if (secretIndexes.contains(i)) {
                sb.append("███");
                continue;
            }
            String s;
            try {
                s = String.valueOf(args[i]);
            } catch (Throwable t) {
                // user objects may have poisonous toString(); the report must survive them
                s = "<toString failed: " + t.getClass().getSimpleName() + ">";
            }
            s = clean(s);
            sb.append(s.length() > MAX_ARG_LENGTH ? s.substring(0, MAX_ARG_LENGTH) + "…" : s);
        }
        if (args.length > MAX_ARGS) sb.append(", …+").append(args.length - MAX_ARGS);
        return sb.toString();
    }

    /** One line per section is part of the format: embedded newlines become literal {@code \n}. */
    private static String flat(String s) {
        if (s == null) return "";
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return s.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    private static String abbreviate(String loggerName) {
        String[] parts = loggerName.split("\\.");
        if (parts.length <= 1) return loggerName;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) sb.append(parts[i].charAt(0)).append('.');
        }
        return sb.append(parts[parts.length - 1]).toString();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
