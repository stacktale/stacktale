package io.github.gabrielbbaldez.stacktale;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Regex-level hygiene for report content: error reports concentrate exactly the data
 * people accidentally log (tokens, credentials, emails, card numbers), and their whole
 * purpose is to be handed to an AI. Applied centrally by the renderer so no capture path
 * can bypass it. This is pattern matching, not semantic PII detection — documented as such.
 */
final class Redactor {

    private static final String MASK = "███";

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
            "(?i)\"(" + SECRET_KEYWORDS + ")s?\"(\\s*:\\s*)\"[^\"]*\"");
    private static final Pattern BEARER_BASIC = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{16,}");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\b");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    private final boolean enabled;
    private final List<Pattern> customPatterns;

    private Redactor(boolean enabled, List<Pattern> customPatterns) {
        this.enabled = enabled;
        this.customPatterns = customPatterns;
    }

    static Redactor withDefaults(List<Pattern> customPatterns) {
        return new Redactor(true, new ArrayList<>(customPatterns));
    }

    static Redactor disabled() {
        return new Redactor(false, List.of());
    }

    boolean isEnabled() {
        return enabled;
    }

    String redact(String s) {
        if (!enabled || s == null || s.isEmpty()) return s;
        try {
            // specific before generic: KEY_VALUE would otherwise match "Authorization: Bearer"
            // and mask the word "Bearer" while leaving the token itself exposed
            s = JWT.matcher(s).replaceAll(MASK);
            s = BEARER_BASIC.matcher(s).replaceAll("$1 " + MASK);
            s = JSON_KEY_VALUE.matcher(s).replaceAll("\"$1\"$2\"" + MASK + "\"");
            s = KEY_VALUE.matcher(s).replaceAll("$1$2" + MASK);
            s = LONG_HEX.matcher(s).replaceAll(MASK);
            s = EMAIL.matcher(s).replaceAll(MASK);
            s = CARD_CANDIDATE.matcher(s).replaceAll(Redactor::maskIfLuhnValid);
            for (Pattern p : customPatterns) {
                s = p.matcher(s).replaceAll(MASK);
            }
            return s;
        } catch (Throwable t) {
            return s; // a broken pattern must never break a report
        }
    }

    private static String maskIfLuhnValid(MatchResult m) {
        String digits = m.group().replaceAll("\\D", "");
        return digits.length() >= 13 && digits.length() <= 19 && luhn(digits) ? MASK : m.group();
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
