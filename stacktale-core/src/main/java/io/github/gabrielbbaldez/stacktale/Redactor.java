package io.github.gabrielbbaldez.stacktale;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Regex-level hygiene for report content: error reports concentrate exactly the data
 * people accidentally log (tokens, credentials, emails, card numbers), and their whole
 * purpose is to be handed to an AI. Applied centrally by the renderer so no capture path
 * can bypass it. This is pattern matching, not semantic PII detection — documented as such.
 *
 * <p><b>Correlation tokens (opt-in).</b> Masking every value to an identical {@code ███}
 * is the safe default, but it erases a signal the report exists to carry: an AI can no
 * longer tell whether the <em>same</em> email/token keeps failing ("one customer or
 * many?"). With correlation on, a masked value becomes {@code ███(a1b2)} where the suffix
 * is a truncated keyed hash (HMAC-SHA-256 under a per-process random key). Same value ⇒
 * same suffix, so repetition is visible, while the value stays irreversible without the
 * key. Low-entropy values (a boolean, a short id) are guessable even keyed, so the suffix
 * is applied only above {@link #MIN_CORRELATION_LENGTH}; below it, the plain mask is used.
 */
final class Redactor {

    private static final String MASK = "███";

    /** Below this raw length a keyed suffix leaks too much (small domain), so plain-mask. */
    private static final int MIN_CORRELATION_LENGTH = 8;

    /**
     * How long every custom pattern gets, together, on one string. Redaction runs on the
     * application's logging thread inside the user's {@code log.error(...)}, so this is time
     * their request is blocked. The built-in rules finish a few KB in microseconds; a sane
     * custom rule is in the same class, and 100ms leaves three orders of magnitude of slack.
     */
    private static final long CUSTOM_PATTERN_BUDGET_NANOS = 100_000_000L;

    /**
     * Per-process HMAC key: correlation is session-scoped (like {@code seen:}). A random
     * key means the suffix is a stable equality signal within one run, never a rainbow-table
     * target across runs or a hash an attacker can precompute from a guessed value.
     */
    private static final byte[] CORRELATION_KEY = randomKey();

    /**
     * Secret-ish key names, shared with the renderer's arg-position heuristic. Not just
     * English: logs are written in the developer's language and "senha=hunter2" leaks
     * exactly like "password=hunter2". Kept conservative — every word here is one that,
     * followed by =/:, is overwhelmingly a credential.
     */
    static final String SECRET_KEYWORDS =
            "password|passwd|pwd|secret|token|api[_-]?key|authorization|credential"
                    + "|senha|segredo|chave|contrase[nñ]a|clave|secreto|passwort|kennwort|mot[ _-]de[ _-]passe";

    // the value may be "Bearer <token>"/"Basic <creds>" — swallow the scheme word AND the
    // token, otherwise "Authorization: Basic dXNlcjpwYXNz" masks the word and leaks the creds
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(" + SECRET_KEYWORDS + ")s?\\b(\\s*[=:]\\s*)((?:(?:bearer|basic)\\s+)?\\S+)");
    // JSON-style quoted keys: {"password":"hunter2"}
    private static final Pattern JSON_KEY_VALUE = Pattern.compile(
            "(?i)\"(" + SECRET_KEYWORDS + ")s?\"(\\s*:\\s*)\"([^\"]*)\"");
    private static final Pattern BEARER_BASIC = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+([A-Za-z0-9._~+/=-]{16,})");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\b");
    /**
     * Credentials recognisable on their own, by the prefix the vendor put on them.
     *
     * <p>Every other rule here needs <em>context</em> — a keyword before the value, a scheme
     * word, a quoted JSON member. A credential in an ordinary sentence has none:
     * {@code "upload failed for key AKIAIOSFODNN7EXAMPLE"} is not {@code key=…}, not hex, not a
     * JWT, and used to travel intact into a file that gets attached to tickets and CI artifacts
     * (#221; `audit_redaction` in stacktale-mcp is what found it).
     *
     * <p>These are safe to mask on shape alone because the prefix is the evidence: {@code AKIA}
     * followed by sixteen upper-alphanumerics is an AWS access key id and essentially nothing
     * else. There is deliberately no entropy heuristic — on a file of stack traces that fires on
     * class names, base64 payloads and hashes, and a redactor that eats class names is worse
     * than one that misses a token.
     *
     * <p>One alternation rather than six patterns: one pass over the value, and {@code Pattern}
     * can still pre-scan for the literal prefixes, so a value containing none of them is
     * rejected without entering the machine.
     */
    private static final Pattern VENDOR_TOKEN = Pattern.compile(
            "\\b(?:(?:AKIA|ASIA)[0-9A-Z]{16}"              // AWS access key id
            + "|gh[pousr]_[A-Za-z0-9]{36,}"                 // GitHub token
            + "|sk-(?:proj-)?[A-Za-z0-9_-]{20,}"            // OpenAI-style key
            + "|(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}"   // Stripe secret key
            + "|xox[baprs]-[A-Za-z0-9-]{10,}"               // Slack token
            // {35,} rather than {35}: a Google key is 39 characters, but an exact count leaves
            // the tail of anything longer sitting in the report next to a ███, which reads as
            // masked and is not
            + "|AIza[0-9A-Za-z_-]{35,})");                  // Google API key

    /**
     * A PEM private key, masked from its header to the end of the value.
     *
     * <p>Not just the header line: the header is harmless and the bytes after it are the key.
     * Whatever else shares the value with a private key is not worth preserving, so this takes
     * the remainder — including across newlines, since redaction runs before values are
     * flattened to one line.
     */
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*");

    /**
     * Cheap gate in front of {@link #VENDOR_TOKEN}.
     *
     * <p>An alternation of six branches has no single required literal, so the engine tries
     * every branch at every position of every value — and nearly every value in a report
     * contains none of them. {@code indexOf} on a short literal is intrinsified and settles
     * that in a fraction of the time. Each marker is chosen to be rare in ordinary text:
     * {@code gh} would fire on "through" and "light", {@code ghp_} does not.
     */
    private static boolean mayCarryVendorToken(String s) {
        return s.indexOf("AKIA") >= 0 || s.indexOf("ASIA") >= 0
                || s.indexOf("ghp_") >= 0 || s.indexOf("gho_") >= 0 || s.indexOf("ghu_") >= 0
                || s.indexOf("ghs_") >= 0 || s.indexOf("ghr_") >= 0
                || s.indexOf("sk-") >= 0 || s.indexOf("sk_") >= 0 || s.indexOf("rk_") >= 0
                || s.indexOf("xox") >= 0 || s.indexOf("AIza") >= 0;
    }

    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    private final boolean enabled;
    private final boolean correlate;
    private final List<Pattern> customPatterns;

    private Redactor(boolean enabled, boolean correlate, List<Pattern> customPatterns) {
        this.enabled = enabled;
        this.correlate = correlate;
        this.customPatterns = customPatterns;
    }

    static Redactor withDefaults(List<Pattern> customPatterns) {
        return withDefaults(customPatterns, false);
    }

    static Redactor withDefaults(List<Pattern> customPatterns, boolean correlate) {
        return new Redactor(true, correlate, new ArrayList<>(customPatterns));
    }

    static Redactor disabled() {
        return new Redactor(false, false, List.of());
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Matches a secret-ish keyword sitting right before a {@code {}} placeholder in the pattern. */
    private static final Pattern SECRET_BEFORE_PLACEHOLDER = Pattern.compile(
            "(?i)\\b(" + SECRET_KEYWORDS + ")s?\\b\\s*[=:]?\\s*$");

    /**
     * Arg positions whose SLF4J {@code {}} placeholder is preceded by a secret keyword
     * ({@code "password={}"} hides the secret in the arg, out of reach of the name-based
     * value rules). Shared by the text and JSON renderers.
     */
    java.util.Set<Integer> secretArgIndexes(String pattern) {
        if (!enabled || pattern == null || pattern.indexOf('{') < 0) return java.util.Set.of();
        java.util.Set<Integer> out = new java.util.HashSet<>();
        int index = 0;
        int from = 0;
        int at;
        while ((at = pattern.indexOf("{}", from)) >= 0) {
            if (SECRET_BEFORE_PLACEHOLDER.matcher(pattern.substring(0, at)).find()) out.add(index);
            index++;
            from = at + 2;
        }
        return out;
    }

    String redact(String s) {
        if (!enabled || s == null || s.isEmpty()) return s;
        try {
            // specific before generic: KEY_VALUE would otherwise match "Authorization: Bearer"
            // and mask the word "Bearer" while leaving the token itself exposed
            s = PRIVATE_KEY_BLOCK.matcher(s).replaceAll(m -> mask(m.group()));
            s = JWT.matcher(s).replaceAll(m -> mask(m.group()));
            // before the context rules: those would mask `key=AKIA…` anyway, this one also
            // catches the same token with nothing beside it
            if (mayCarryVendorToken(s)) s = VENDOR_TOKEN.matcher(s).replaceAll(m -> mask(m.group()));
            s = BEARER_BASIC.matcher(s).replaceAll(m -> m.group(1) + " " + mask(m.group(2)));
            s = JSON_KEY_VALUE.matcher(s).replaceAll(
                    m -> '"' + m.group(1) + '"' + m.group(2) + '"' + mask(m.group(3)) + '"');
            s = KEY_VALUE.matcher(s).replaceAll(m -> m.group(1) + m.group(2) + mask(m.group(3)));
            s = LONG_HEX.matcher(s).replaceAll(m -> mask(m.group()));
            s = EMAIL.matcher(s).replaceAll(m -> mask(m.group()));
            s = CARD_CANDIDATE.matcher(s).replaceAll(this::maskIfLuhnValid);
            // One budget for all custom rules together, not one each: a config with twenty
            // patterns must not buy twenty times as much of the caller's thread.
            long expiresAt = System.nanoTime() + CUSTOM_PATTERN_BUDGET_NANOS;
            for (Pattern p : customPatterns) {
                s = p.matcher(new Deadline(s, expiresAt)).replaceAll(m -> mask(m.group()));
            }
            return s;
        } catch (Throwable t) {
            // A pattern that is broken, or one stopped by the deadline, must never break a
            // report. `s` still carries every built-in rule, because they reassign it in turn.
            return s;
        }
    }

    /**
     * Bounds how long one custom pattern may run.
     *
     * <p>{@link java.util.regex.Matcher} has no timeout and no cancel. The one hook a caller
     * gets into a match already under way is {@code charAt}, which the engine calls for every
     * character it examines — so a pattern backtracking exponentially calls it billions of
     * times, and a deadline checked there is what stops it.
     *
     * <p>The check is sampled rather than made on every call, because {@code nanoTime()} on
     * each character would cost more than the matching it guards.
     *
     * <p>Worth knowing for anyone reproducing this: most textbook catastrophic patterns do
     * <em>not</em> hang here. {@code Pattern} extracts a required literal and pre-scans for it
     * with {@code indexOf}, so {@code (x+x+)+y} against a string with no {@code y} returns at
     * once without entering the loop. The shapes that do reach the loop are the ones with
     * nothing scannable — a backreference tail such as {@code (a+)+\1b} is the reliable one,
     * and it goes from 15ms to 30s between a 18- and a 30-character input.
     */
    private static final class Deadline implements CharSequence {

        private static final int SAMPLE_EVERY = 4096;

        private final CharSequence text;
        private final long expiresAtNanos;
        private int untilNextCheck = SAMPLE_EVERY;

        Deadline(CharSequence text, long expiresAtNanos) {
            this.text = text;
            this.expiresAtNanos = expiresAtNanos;
        }

        @Override
        public char charAt(int index) {
            if (--untilNextCheck <= 0) {
                untilNextCheck = SAMPLE_EVERY;
                // subtraction, not <: nanoTime() has no fixed origin and can be negative
                if (System.nanoTime() - expiresAtNanos > 0) throw new RedactionTooSlow();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /** Control flow, not a fault: no message, no stack, nothing to fill in or read. */
    private static final class RedactionTooSlow extends RuntimeException {
        RedactionTooSlow() {
            super(null, null, false, false);
        }
    }

    /**
     * The mask for one raw secret: plain {@code ███}, or {@code ███(token)} when correlation
     * is on and the value is long enough that a keyed suffix is safe.
     */
    private String mask(String rawSecret) {
        if (!correlate || rawSecret == null || rawSecret.length() < MIN_CORRELATION_LENGTH) return MASK;
        String token = correlationToken(rawSecret);
        return token == null ? MASK : MASK + "(" + token + ")";
    }

    private String maskIfLuhnValid(MatchResult m) {
        String digits = m.group().replaceAll("\\D", "");
        return digits.length() >= 13 && digits.length() <= 19 && luhn(digits) ? mask(digits) : m.group();
    }

    /** 4 hex chars of HMAC-SHA-256 under the per-process key: enough to distinguish, too short to be a fingerprint. */
    private static String correlationToken(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CORRELATION_KEY, "HmacSHA256"));
            byte[] h = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x", h[0] & 0xff, h[1] & 0xff);
        } catch (Exception e) {
            return null; // no HMAC provider (never on a standard JRE) → fall back to plain mask
        }
    }

    private static byte[] randomKey() {
        byte[] k = new byte[16];
        new SecureRandom().nextBytes(k);
        return k;
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alternate) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
