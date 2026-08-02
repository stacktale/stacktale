package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    private final Redactor redactor = Redactor.withDefaults(List.of());

    @Test
    void masksSecretKeyValuePairsKeepingTheKey() {
        assertThat(redactor.redact("login failed password=hunter2 for bob"))
                .isEqualTo("login failed password=███ for bob");
        assertThat(redactor.redact("apiKey: sk-live-1234567890"))
                .isEqualTo("apiKey: ███");
    }

    @Test
    void masksBearerAndJwt() {
        assertThat(redactor.redact("header Authorization: Bearer abcdef1234567890TOKENVALUE"))
                .doesNotContain("TOKENVALUE");
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9P";
        assertThat(redactor.redact("token was " + jwt)).doesNotContain(jwt).contains("███");
    }

    @Test
    void masksLongHexSecrets() {
        assertThat(redactor.redact("sha af52c1d94ee1e33a8ff2fba4bdbf28a1af52c1d94ee1e33a"))
                .doesNotContain("af52c1d94ee1e33a8ff2fba4bdbf28a1");
    }

    @Test
    void masksEmails() {
        assertThat(redactor.redact("user gabriel@example.com not found"))
                .isEqualTo("user ███ not found");
    }

    @Test
    void masksCardNumbersOnlyWhenLuhnValid() {
        assertThat(redactor.redact("card 4532 0151 1283 0366 declined"))   // Luhn-valid
                .doesNotContain("4532").contains("███");
        assertThat(redactor.redact("trace id 1234 5678 9012 3456 kept"))   // Luhn-invalid
                .contains("1234 5678 9012 3456");
    }

    @Test
    void masksShortBasicCredentialsEntirely() {
        // "Basic dXNlcjpwYXNz" is under the 16-char shape threshold; the key=value rule
        // must swallow the scheme word AND the credential, not just the word "Basic"
        String out = redactor.redact("header Authorization: Basic dXNlcjpwYXNz rejected");
        assertThat(out).doesNotContain("dXNlcjpwYXNz");
    }

    @Test
    void masksNonEnglishSecretKeywords() {
        assertThat(redactor.redact("login falhou senha=hunter2 para bob")).isEqualTo("login falhou senha=███ para bob");
        assertThat(redactor.redact("contraseña: hunter2 rechazada")).doesNotContain("hunter2");
        assertThat(redactor.redact("Passwort=hunter2 ungültig")).doesNotContain("hunter2");
        assertThat(redactor.redact("chave: sk-live-123 expirada")).doesNotContain("sk-live-123");
        // words that merely CONTAIN a keyword must not trigger
        assertThat(redactor.redact("a senhora aprovou o pedido 42")).isEqualTo("a senhora aprovou o pedido 42");
        // keyword without a separator is prose, not a credential
        assertThat(redactor.redact("a chave do problema era o cache")).contains("chave do problema");
    }

    @Test
    void masksJsonQuotedSecretKeys() {
        String out = redactor.redact("request body {\"user\":\"bob\",\"password\":\"hunter2\"}");
        assertThat(out).doesNotContain("hunter2").contains("\"user\":\"bob\"");
    }

    @Test
    void leavesNormalTextAlone() {
        String s = "order 889 failed with status 502 after 800ms (git 7e3c1f)";
        assertThat(redactor.redact(s)).isEqualTo(s);
    }

    @Test
    void customPatternsApply() {
        Redactor custom = Redactor.withDefaults(List.of(Pattern.compile("BR\\d{2}-\\d{4}")));
        assertThat(custom.redact("internal id BR12-9944 leaked")).isEqualTo("internal id ███ leaked");
    }

    @Test
    void disabledPassesThrough() {
        assertThat(Redactor.disabled().redact("password=hunter2")).isEqualTo("password=hunter2");
    }

    // --- correlation-preserving redaction (opt-in) -------------------------------------

    @Test
    void correlationTokenIsStablePerValueAndHidesTheValue() {
        Redactor c = Redactor.withDefaults(List.of(), true);
        String first = c.redact("checkout failed for gabriel@example.com");
        String again = c.redact("retry failed for gabriel@example.com");
        // the raw email never reaches the file, but the SAME email → the SAME 4-hex suffix,
        // so an AI reading two occurrences sees it is one customer, not two
        assertThat(first).doesNotContain("gabriel@example.com").contains("███(");
        assertThat(tokenOf(first)).hasSize(4).isEqualTo(tokenOf(again));
    }

    @Test
    void correlationDistinguishesDifferentValues() {
        Redactor c = Redactor.withDefaults(List.of(), true);
        assertThat(tokenOf(c.redact("user alice@example.com")))
                .isNotEqualTo(tokenOf(c.redact("user bob.roberts@example.com")));
    }

    @Test
    void correlationSkipsShortLowEntropyValues() {
        Redactor c = Redactor.withDefaults(List.of(), true);
        // a 6-char value's keyed hash is guessable from a tiny domain → plain mask, no token
        assertThat(c.redact("ping a@b.co now")).isEqualTo("ping ███ now");
    }

    @Test
    void correlationOffByDefaultKeepsPlainMask() {
        assertThat(redactor.redact("checkout failed for gabriel@example.com"))
                .isEqualTo("checkout failed for ███");
    }

    private static String tokenOf(String redacted) {
        java.util.regex.Matcher m = Pattern.compile("███\\(([0-9a-f]+)\\)").matcher(redacted);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void aCatastrophicCustomPatternCannotWedgeTheLoggingThread() {
        // A nested quantifier alone is not enough: Pattern pulls out a required literal and
        // pre-scans for it, so (x+x+)+y over a string with no 'y' returns immediately. A
        // backreference leaves nothing to scan for, so the engine has to walk every split.
        // Unpatched this takes ~3.8s at 27 characters and ~30s at 30.
        Redactor redactor = Redactor.withDefaults(List.of(Pattern.compile("(a+)+\\1b")));
        String hostile = "a".repeat(30);

        long startedAt = System.nanoTime();
        String out = redactor.redact(hostile);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        // redact() runs on the application's logging thread, inside the user's log.error call,
        // so this is time a request is blocked. The budget is 100ms; allow for a loaded runner.
        assertThat(elapsedMillis).isLessThan(2_000);
        assertThat(out).isEqualTo(hostile); // nothing matched, and nothing was lost
    }

    @Test
    void theDeadlineIsSharedAcrossPatternsRatherThanGrantedToEach() {
        // Ten copies of the same runaway rule must cost one budget, not ten.
        Pattern runaway = Pattern.compile("(a+)+\\1b");
        Redactor redactor = Redactor.withDefaults(
                java.util.Collections.nCopies(10, runaway));

        long startedAt = System.nanoTime();
        redactor.redact("a".repeat(30));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_000);
    }

    @Test
    void aSlowPatternCostsOnlyItsOwnRuleAndNotTheBuiltInOnes() {
        // The built-ins reassign `s` in turn before the custom loop, so the catch that swallows
        // the deadline still returns everything they masked. Degraded, not discarded.
        Redactor redactor = Redactor.withDefaults(List.of(Pattern.compile("(a+)+\\1b")));

        String out = redactor.redact("contact bob@example.com about " + "a".repeat(30));

        assertThat(out).doesNotContain("bob@example.com");
        assertThat(out).contains("███");
    }
}
