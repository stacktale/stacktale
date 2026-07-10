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
}
