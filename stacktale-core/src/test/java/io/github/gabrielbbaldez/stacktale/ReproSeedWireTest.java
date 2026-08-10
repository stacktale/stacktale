package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core half of the agent wire format (#135).
 *
 * <p>The emitter is in {@code stacktale-agent} and the parser is here, in two artifacts a user
 * can pin to different versions. Nothing else compares them, so these assertions are written
 * against literal lines rather than against anything the agent produces — a drift on either
 * side has to fail one of the two suites.
 */
class ReproSeedWireTest {

    private static final List<String> ONE_FRAME = List.of(
            "m com.acme.shop.PaymentService charge",
            "p long orderId 889",
            "p java.math.BigDecimal amount 149.90");

    @Test
    void parsesTheFullyQualifiedCallWithDeclaredTypes() {
        ReproSeed seed = AgentCaptures.parseSeed(ONE_FRAME);

        assertThat(seed).isNotNull();
        assertThat(seed.className()).isEqualTo("com.acme.shop.PaymentService");
        assertThat(seed.simpleClassName()).isEqualTo("PaymentService");
        assertThat(seed.methodName()).isEqualTo("charge");
        assertThat(seed.params()).extracting(ReproSeed.Param::type)
                .containsExactly("long", "java.math.BigDecimal");
        assertThat(seed.params()).extracting(ReproSeed.Param::name)
                .containsExactly("orderId", "amount");
    }

    @Test
    void onlyTheInnermostFrameBecomesTheSeed() {
        // Captures are appended as the throwable unwinds, so the first frame is the closest to
        // the throw. A seed naming the outer caller would describe a call that did not fail.
        ReproSeed seed = AgentCaptures.parseSeed(List.of(
                "m com.acme.shop.PaymentService charge",
                "p long orderId 889",
                "m com.acme.shop.OrderService confirm",
                "p long id 42"));

        assertThat(seed.methodName()).isEqualTo("charge");
        assertThat(seed.params()).extracting(ReproSeed.Param::name).containsExactly("orderId");
    }

    @Test
    void aValueContainingSpacesSurvives() {
        // The value takes the remainder of the line for exactly this reason: exception
        // messages and toString() output routinely contain spaces, types and names never do.
        ReproSeed seed = AgentCaptures.parseSeed(List.of(
                "m com.acme.Svc handle",
                "p java.lang.String note some text with spaces"));

        assertThat(seed.params().get(0).value()).isEqualTo("some text with spaces");
    }

    @Test
    void aTruncatedOrForeignLineIsIgnoredRatherThanGuessed() {
        assertThat(AgentCaptures.parseSeed(List.of("m com.acme.Svc"))).isNull();
        assertThat(AgentCaptures.parseSeed(List.of("something else entirely"))).isNull();
        assertThat(AgentCaptures.parseSeed(List.of())).isNull();
        assertThat(AgentCaptures.parseSeed(null)).isNull();

        // a malformed parameter drops that parameter, not the whole seed: a signature with one
        // argument missing still names the method, which is most of the value
        ReproSeed partial = AgentCaptures.parseSeed(List.of(
                "m com.acme.Svc handle", "p broken", "p int ok 1"));
        assertThat(partial.params()).extracting(ReproSeed.Param::name).containsExactly("ok");
    }

    @Test
    void argumentValuesGoThroughRedaction() {
        // The seed is the only section that renders values against a named signature. If
        // redaction did not reach here it would be the easiest place in the report to leak.
        ReproSeed seed = AgentCaptures.parseSeed(List.of(
                "m com.acme.Auth login",
                "p java.lang.String password hunter2",
                "p java.lang.String email someone@example.com"));
        Report r = new Report("dead", 1_000L, "main", null, "login failed", null, "com.acme.Auth",
                java.util.Map.of(), java.util.Map.of(), List.of(),
                new Story(List.of(), "thread main"), "app=? | java 21 | linux", 1, 0L, seed);

        String rendered = new ReportRenderer(java.time.ZoneOffset.UTC).render(r);

        assertThat(rendered).contains("com.acme.Auth#login");
        assertThat(rendered).doesNotContain("hunter2");
        assertThat(rendered).doesNotContain("someone@example.com");
        assertThat(rendered).contains("███");
    }
}
