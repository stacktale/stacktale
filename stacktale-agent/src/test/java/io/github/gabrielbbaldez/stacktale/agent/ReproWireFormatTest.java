package io.github.gabrielbbaldez.stacktale.agent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emitting half of the repro wire format (#135). Its parser lives in {@code
 * stacktale-core}, in a different artifact — nothing compares the two at build time, so both
 * sides assert against literal lines and a drift has to break one of them.
 */
class ReproWireFormatTest {

    /** A method with declared types worth naming: a primitive, an object, and a boxed value. */
    static final class Billing {
        void charge(long orderId, BigDecimal amount, boolean express) {
            throw new IllegalStateException("gateway refused");
        }
    }

    @Test
    void emitsTheFullyQualifiedCallAndDeclaredParameterTypes() {
        Throwable thrown = new IllegalStateException("gateway refused");
        CaptureRegistry.record(thrown, Billing.class.getName(), "charge",
                new Object[]{889L, new BigDecimal("149.90"), true});

        List<String> lines = CaptureRegistry.repro(thrown);

        assertThat(lines.get(0))
                .isEqualTo("m " + Billing.class.getName() + " charge");
        // declared types, not runtime ones: `long` rather than java.lang.Long, and
        // java.math.BigDecimal rather than whatever subclass arrived. A signature is only
        // reconstructable from what the method declares.
        assertThat(lines).contains(
                "p long orderId 889",
                "p java.math.BigDecimal amount 149.90",
                "p boolean express true");
    }

    @Test
    void theCompactCapturedLineIsUnchangedByAnyOfThis() {
        Throwable thrown = new IllegalStateException("gateway refused");
        CaptureRegistry.record(thrown, Billing.class.getName(), "charge",
                new Object[]{889L, new BigDecimal("149.90"), true});

        // The captured: section is pinned by golden files in stacktale-core. Recording
        // structured frames and rendering per reader must not have moved it.
        //
        // Nested classes keep the Outer$Inner form: the compact line strips the package by
        // cutting at the last dot, and a $ is not one. Long-standing behaviour, asserted here
        // so the restructuring is shown not to have changed it either.
        assertThat(CaptureRegistry.get(thrown)).containsExactly(
                "ReproWireFormatTest$Billing.charge(orderId=889, amount=149.90, express=true)");
    }

    @Test
    void anUnresolvableMethodStillNamesSomethingUsable() {
        Throwable thrown = new IllegalStateException("boom");
        // a class that does not exist here: reflection cannot find the declared types, so the
        // runtime class is used rather than dropping the parameter
        CaptureRegistry.record(thrown, "com.nowhere.Ghost", "vanish", new Object[]{"x"});

        assertThat(CaptureRegistry.repro(thrown))
                .containsExactly("m com.nowhere.Ghost vanish", "p java.lang.String arg0 x");
    }

    @Test
    void nothingCapturedMeansNoSeedRatherThanAnEmptyOne() {
        assertThat(CaptureRegistry.repro(new IllegalStateException("never recorded"))).isEmpty();
    }
}
