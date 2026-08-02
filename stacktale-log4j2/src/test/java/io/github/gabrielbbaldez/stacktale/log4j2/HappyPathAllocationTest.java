package io.github.gabrielbbaldez.stacktale.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cheap happy path, which CONTRIBUTING names as a project invariant: a non-error event
 * must not allocate.
 *
 * <p>{@code adapt()} runs for every event, and {@code ReadOnlyStringMap.toMap()} is
 * {@code new HashMap<>(size())} plus a copy loop — done unconditionally, empty context or not.
 * Every application that never touches the ThreadContext, which is most of them, paid for a
 * map per event (#123).
 *
 * <p>Asserted here rather than left to {@code AppendBenchmark}, which is Logback-only and
 * which CI does not run.
 */
class HappyPathAllocationTest {

    private static LogEvent event(SortedArrayStringMap contextData) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("com.acme.OrderService")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage("POST /orders/42/confirm"))
                .setContextData(contextData)
                .build();
    }

    @Test
    void anEmptyThreadContextDoesNotAllocateAMap() {
        Map<String, String> first = StacktaleAppender.contextData(event(new SortedArrayStringMap()));
        Map<String, String> second = StacktaleAppender.contextData(event(new SortedArrayStringMap()));

        // Map.of() returns the shared empty instance, so identity across two calls is a
        // reliable stand-in for "nothing was allocated" — toMap() hands back a fresh HashMap
        // every time, and two of those could never be the same object.
        assertThat(first).isEmpty();
        assertThat(first).isSameAs(second);
    }

    @Test
    void aPopulatedThreadContextStillArrivesInFull() {
        SortedArrayStringMap data = new SortedArrayStringMap();
        data.putValue("traceId", "7b2c");
        data.putValue("orderId", "42");

        Map<String, String> mdc = StacktaleAppender.contextData(event(data));

        // the skip must be the empty case only; a real context still has to reach the report
        assertThat(mdc).containsEntry("traceId", "7b2c").containsEntry("orderId", "42");
    }

    @Test
    void anAbsentContextDataIsTreatedAsEmptyRatherThanThrowing() {
        LogEvent withoutContext = Log4jLogEvent.newBuilder()
                .setLoggerName("com.acme.OrderService")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage("hello"))
                .build();

        assertThat(StacktaleAppender.contextData(withoutContext)).isEmpty();
    }
}
