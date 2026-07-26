package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprinterTest {

    @Test
    void sameErrorSameIdEvenWithDifferentNumbersInMessage() {
        String a = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "failed order 123");
        String b = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "failed order 456");
        assertThat(a).isEqualTo(b).hasSize(8).matches("[0-9a-f]{8}");
    }

    /**
     * Replaces an earlier {@code differentLineDifferentId}, which asserted the opposite.
     * That was a description of how the id happened to be computed rather than a goal worth
     * keeping: an identity that dissolves whenever the file is edited is the wrong identity
     * for a tool whose flagship feature is telling an agent whether its edit fixed anything.
     * See {@link #shiftingTheCulpritsLineKeepsTheSameId}.
     */
    @Test
    void twoThrowSitesInOneMethodAreOneError() {
        String a = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:87)", "x");
        String b = Fingerprinter.fingerprint("NullPointerException", "OrderService.confirm(OrderService.java:88)", "x");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void hexAddressesNormalized() {
        String a = Fingerprinter.fingerprint("OutOfMemoryError", "A.m(A.java:1)", "direct buffer at 0xdeadbeef");
        String b = Fingerprinter.fingerprint("OutOfMemoryError", "A.m(A.java:1)", "direct buffer at 0xcafebabe");
        assertThat(a).isEqualTo(b);
    }

    /**
     * The fix-loop's identity must survive the agent editing the file. Adding a guard clause
     * above the throw site shifts the frame; the error is the same one, still unfixed, and
     * {@code errors_since_last_check} has to keep calling it 🔁 rather than 🆕.
     */
    @Test
    void shiftingTheCulpritsLineKeepsTheSameId() {
        String before = Fingerprinter.fingerprint(
                "NullPointerException", "OrderService.confirm(OrderService.java:87)", "customer is null");
        String after = Fingerprinter.fingerprint(
                "NullPointerException", "OrderService.confirm(OrderService.java:91)", "customer is null");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void adifferentMethodIsStillADifferentError() {
        String confirm = Fingerprinter.fingerprint(
                "NullPointerException", "OrderService.confirm(OrderService.java:87)", "customer is null");
        String cancel = Fingerprinter.fingerprint(
                "NullPointerException", "OrderService.cancel(OrderService.java:87)", "customer is null");
        assertThat(cancel).isNotEqualTo(confirm);
    }

    /** Frames with no line info (-1, or a native method's -2) must not confuse the stripper. */
    @Test
    void handlesFramesWithoutLineNumbers() {
        String unknown = Fingerprinter.fingerprint("IllegalStateException", "A.m(A.java:-1)", "x");
        String known = Fingerprinter.fingerprint("IllegalStateException", "A.m(A.java:12)", "x");
        assertThat(unknown).isEqualTo(known).hasSize(8);
        assertThat(Fingerprinter.fingerprint("IllegalStateException", "A.m(Unknown Source)", "x")).hasSize(8);
    }

    @Test
    void nullSafe() {
        assertThat(Fingerprinter.fingerprint(null, null, null)).hasSize(8);
    }
}
