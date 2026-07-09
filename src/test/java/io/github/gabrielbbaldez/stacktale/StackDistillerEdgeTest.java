package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Hostile-input edges: empty stacks, null messages, circular cause chains. */
class StackDistillerEdgeTest {

    /** Minimal hand-rolled proxy so we can build shapes real Throwables can't (e.g. cycles). */
    private static final class FakeProxy implements IThrowableProxy {
        private final String className;
        private final String message;
        private IThrowableProxy cause;

        FakeProxy(String className, String message) {
            this.className = className;
            this.message = message;
        }

        @Override public String getMessage() { return message; }
        @Override public String getClassName() { return className; }
        @Override public StackTraceElementProxy[] getStackTraceElementProxyArray() { return new StackTraceElementProxy[0]; }
        @Override public int getCommonFrames() { return 0; }
        @Override public IThrowableProxy getCause() { return cause; }
        @Override public IThrowableProxy[] getSuppressed() { return null; }
        @Override public boolean isCyclic() { return false; }
    }

    @Test
    void survivesEmptyStackTrace() {
        RuntimeException e = new RuntimeException("no frames");
        e.setStackTrace(new StackTraceElement[0]);

        DistilledStack d = new StackDistiller(List.of()).distill(new ThrowableProxy(e));

        assertThat(d.rootType()).isEqualTo("RuntimeException");
        assertThat(d.culpritLine()).isNull();
        assertThat(d.frameLines()).isEmpty();
        assertThat(d.totalFrames()).isZero();
    }

    @Test
    void survivesNullMessage() {
        RuntimeException e = new RuntimeException((String) null);
        e.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.acme.A", "m", "A.java", 1)});

        DistilledStack d = new StackDistiller(List.of()).distill(new ThrowableProxy(e));

        assertThat(d.rootMessage()).isNull();
    }

    @Test
    void terminatesOnCircularCauseChains() {
        FakeProxy a = new FakeProxy("com.acme.AException", "a");
        FakeProxy b = new FakeProxy("com.acme.BException", "b");
        a.cause = b;
        b.cause = a;

        StackDistiller distiller = new StackDistiller(List.of());
        assertThatCode(() -> distiller.distill(a)).doesNotThrowAnyException();
    }
}
