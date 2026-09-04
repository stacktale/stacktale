package io.github.gabrielbbaldez.stacktale.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans written reports for values that look like credentials the redactor did not mask.
 *
 * <p>Defence in depth, and specifically a check on the gap the core redactor has by design: it
 * masks a secret it can <em>recognise by context</em> — {@code password=…}, a JSON
 * {@code "token"} member, {@code Authorization: Bearer …}, a long hex run — so a credential
 * with none of that around it travels as an ordinary word. {@code AKIAIOSFODNN7EXAMPLE} in the
 * middle of a log message is not next to a keyword, is not hex, and is not a JWT.
 *
 * <p>What makes those findable anyway is that the vendors gave them fixed prefixes. This looks
 * for exactly that: shapes strong enough to name without guessing, so a team can check the
 * file rather than trust it before pasting it into a PR or shipping it to CI (#95).
 *
 * <p><strong>Nothing here ever prints the value it found.</strong> The answer goes to an
 * assistant and into a transcript, so an audit that quotes the secret has moved it somewhere
 * new — which is the thing being warned about. A finding carries the report id, the line, the
 * rule and the vendor prefix; enough to go and look, useless on its own.
 */
final class RedactionAudit {

    /** A shape strong enough to name. The prefix is the evidence; the rest is never shown. */
    private record Rule(String name, Pattern pattern, String what) {
    }

    /**
     * Prefixed credentials, because a prefix is what survives having no context around it.
     * Deliberately no "high entropy string" rule: on a file of stack traces it fires on class
     * names, base64 payloads and hashes, and an audit nobody trusts gets switched off.
     */
    private static final List<Rule> RULES = List.of(
            new Rule("aws-access-key-id", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"),
                    "an AWS access key id"),
            new Rule("github-token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
                    "a GitHub token"),
            new Rule("openai-key", Pattern.compile("\\bsk-(proj-)?[A-Za-z0-9_-]{20,}\\b"),
                    "an OpenAI-style API key"),
            new Rule("stripe-key", Pattern.compile("\\b(sk|rk)_(live|test)_[A-Za-z0-9]{16,}\\b"),
                    "a Stripe secret key"),
            new Rule("slack-token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
                    "a Slack token"),
            new Rule("google-api-key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35,}"),
                    "a Google API key"),
            new Rule("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}"),
                    "a JWT"),
            new Rule("private-key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    "a private key block"),
            new Rule("authorization-header", Pattern.compile("(?i)\\bauthorization\\s*[:=]\\s*(bearer|basic)\\s+\\S{8,}"),
                    "an Authorization header with its credential"));

    /** The mask the core writes. A line already carrying one is evidence redaction ran, not a leak. */
    private static final String MASK = "███";

    private RedactionAudit() {
    }

    /** One line of one report that matched a rule. The value itself is not part of it. */
    private record Finding(String reportId, int line, Rule rule, String prefix, int length) {
    }

    /**
     * The audit over every report the server can see.
     *
     * <p>A clean result is an answer worth returning in full: "nothing matched" is what someone
     * is asking for before they attach the file to a ticket, and it means more when it says what
     * was looked for.
     */
    static String run(List<StReportFile.StReport> reports) {
        List<Finding> findings = new ArrayList<>();
        int masked = 0;
        for (StReportFile.StReport report : reports) {
            String[] lines = report.block().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(MASK)) {
                    masked++;
                }
                for (Rule rule : RULES) {
                    Matcher m = rule.pattern().matcher(lines[i]);
                    if (m.find()) {
                        findings.add(new Finding(report.id(), i + 1, rule,
                                m.group().substring(0, Math.min(4, m.group().length())),
                                m.group().length()));
                    }
                }
            }
        }
        return findings.isEmpty() ? clean(reports.size(), masked) : report(findings, reports.size(), masked);
    }

    private static String clean(int reports, int masked) {
        StringBuilder b = new StringBuilder();
        b.append("✓ No un-redacted credential shapes in ").append(reports).append(" report(s).\n\n");
        b.append("Checked for: ");
        b.append(String.join(", ", RULES.stream().map(Rule::name).toList()));
        b.append(".\n");
        if (masked == 0) {
            // the check that would otherwise pass loudest on a file where redaction never ran
            b.append("\nNo masked values (").append(MASK).append(") anywhere in the file either. That is")
                    .append(" expected when nothing sensitive was logged, and is also what a file written")
                    .append(" with redactionEnabled=false looks like — worth confirming which.\n");
        } else {
            b.append("\n").append(masked).append(" line(s) carry a masked value, so redaction is running.\n");
        }
        b.append("\nThis finds credentials with a known vendor prefix. A secret with no recognisable"
                + " shape and no keyword beside it cannot be found this way, so a clean result is"
                + " evidence rather than proof.\n");
        return b.toString();
    }

    private static String report(List<Finding> findings, int reports, int masked) {
        StringBuilder b = new StringBuilder();
        b.append("⚠ ").append(findings.size()).append(" possible un-redacted credential(s) in ")
                .append(reports).append(" report(s).\n\n");

        Map<String, List<Finding>> byReport = new LinkedHashMap<>();
        for (Finding f : findings) {
            byReport.computeIfAbsent(f.reportId(), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<String, List<Finding>> entry : byReport.entrySet()) {
            b.append("#").append(entry.getKey()).append('\n');
            for (Finding f : entry.getValue()) {
                b.append("  line ").append(f.line()).append(": ").append(f.rule().what())
                        .append(" — starts ").append(f.prefix()).append("…, ")
                        .append(f.length()).append(" chars (")
                        .append(f.rule().name()).append(")\n");
            }
        }
        b.append("\nThe values are not shown: this answer is going into an assistant's context and a"
                + " transcript, and quoting the secret would move it somewhere new. Open the report"
                + " file at those lines to see them.\n");
        b.append("\nWhat to do: treat each as compromised and rotate it — it has been on disk, and"
                + " probably in a CI artifact. Then stop it recurring: add a redactPattern for the"
                + " shape (redactPattern in logback.xml, stacktale.redact-patterns elsewhere), or"
                + " stop logging the value.\n");
        if (masked == 0) {
            b.append("\nNothing in the file is masked at all, which points at redactionEnabled=false"
                    + " rather than at a gap in the rules.\n");
        }
        return b.toString();
    }
}
