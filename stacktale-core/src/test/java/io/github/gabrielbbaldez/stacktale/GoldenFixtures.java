package io.github.gabrielbbaldez.stacktale;

import java.util.List;
import java.util.Map;

/**
 * The reports behind the golden files, shared by both formats.
 *
 * <p>{@code st/1} and {@code st-json/1} carry the same information (FORMAT.md §7), so their
 * conformance suites render the same reports. A fixture changed here moves both sets of goldens
 * at once, which is the point: the two can never drift apart by testing different inputs.
 */
final class GoldenFixtures {

    private GoldenFixtures() {}

    /** golden/full-report.txt: a distilled NPE with framework frames and a four-event story. */
    static Report full() {
        NullPointerException npe = new NullPointerException("customer is null");
        npe.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.shop.OrderService", "confirm", "OrderService.java", 87),
                new StackTraceElement("com.acme.shop.OrderController", "confirm", "OrderController.java", 34),
                new StackTraceElement("org.springframework.web.method.support.InvocableHandlerMethod", "invokeForRequest", "InvocableHandlerMethod.java", 190),
                new StackTraceElement("org.apache.catalina.core.ApplicationFilterChain", "doFilter", "ApplicationFilterChain.java", 166),
        });
        DistilledStack stack = new StackDistiller(List.of("com.acme")).distill(npe);
        Story story = new Story(List.of(
                new StoryEntry(1_000_000L, "INFO", "OrderController", "POST /orders/123/confirm"),
                new StoryEntry(1_000_108L, "INFO", "CustomerClient", "fetching customer 555 → 404"),
                new StoryEntry(1_000_113L, "WARN", "CustomerCache", "miss for 555, returning null"),
                new StoryEntry(1_000_412L, "ERROR", "OrderService", "Failed to confirm order 123")
        ), "thread http-nio-8080-exec-3");
        return new Report("a1b2", 1_000_412L, "http-nio-8080-exec-3", stack,
                "Failed to confirm order {}", new Object[]{123}, "com.acme.shop.OrderService",
                Map.of("traceId", "9f3a", "userId", "42"), Map.of(), List.of(), story,
                "app=shop-api 1.4.2 (git 7e3c1f) | java 21 | profile=dev | linux", 1, 0L);
    }

    /** golden/no-throwable.txt: an ERROR logged without an exception. */
    static Report noThrowable() {
        Story story = new Story(List.of(
                new StoryEntry(2_000_000L, "ERROR", "PaymentService", "payment rejected for order 77")
        ), "thread main");
        return new Report("beef", 2_000_000L, "main", null,
                "payment rejected for order {}", new Object[]{77}, "com.acme.PaymentService",
                Map.of(), Map.of(), List.of(), story, "app=? | java 21 | linux", 1, 0L);
    }

    /** golden/rich-report.txt: wrapped, recurring, with fields, captures and an aged-out story. */
    static Report rich() {
        DistilledStack stack = new DistilledStack("IllegalStateException", "payment gateway refused",
                "PaymentService.charge(PaymentService.java:44)", true,
                List.of("CheckoutException(\"checkout failed\") at CheckoutService.confirm(CheckoutService.java:88)"),
                List.of("PaymentService.charge(PaymentService.java:44) ← culprit",
                        "… 30 collapsed (spring ×20, tomcat ×10)"),
                32, 1, List.of());
        Story story = new Story(List.of(
                new StoryEntry(1_000_050L, "INFO", "CheckoutService", "confirming order 889"),
                new StoryEntry(1_000_412L, "ERROR", "PaymentService", "charge failed for order 889")
        ), "traceId=7c2e", 2);
        return new Report("a1b2c3d4", 1_000_412L, "http-nio-8080-exec-2", stack,
                "charge failed for order {}", new Object[]{889}, "com.acme.shop.PaymentService",
                Map.of("traceId", "7c2e"), Map.of("orderId", "889", "retryable", "false"),
                List.of("PaymentService.charge(orderId=889, amount=149.90)"),
                story, "app=shop-api 1.4.2 (git 7e3c1f) | java 21 | profile=prod | linux",
                3, 1_000_000L);
    }

    /** golden/repro-report.txt: a throw site instrumented by the agent, with its seed. */
    static Report repro() {
        IllegalStateException boom = new IllegalStateException("payment gateway refused");
        boom.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.shop.PaymentService", "charge", "PaymentService.java", 118),
        });
        DistilledStack stack = new StackDistiller(List.of("com.acme")).distill(boom);
        Story story = new Story(List.of(
                new StoryEntry(1_000_412L, "ERROR", "PaymentService", "charge failed for order 889")
        ), "traceId=7c2e");
        ReproSeed seed = new ReproSeed("com.acme.shop.PaymentService", "charge", List.of(
                new ReproSeed.Param("long", "orderId", "889"),
                new ReproSeed.Param("java.math.BigDecimal", "amount", "149.90")));
        return new Report("5eed0001", 1_000_412L, "http-nio-8080-exec-2", stack,
                "charge failed for order {}", new Object[]{889}, "com.acme.shop.PaymentService",
                Map.of("traceId", "7c2e"), Map.of(),
                List.of("PaymentService.charge(orderId=889, amount=149.90)"),
                story, "app=shop 2.1.0 | java 21 | linux", 1, 0L, seed);
    }

    /**
     * {@link #rich()} plus the members no text golden reaches: {@code firstSeen} (with
     * {@code buildsAgo}) and {@code stack.suppressed}. The schema drift guard needs every member
     * the renderer can write to appear in at least one fixture.
     */
    static Report provenance() {
        Report rich = rich();
        DistilledStack s = rich.stack();
        DistilledStack stack = new DistilledStack(s.rootType(), s.rootMessage(), s.culpritLine(),
                s.culpritIsAppCode(), s.wrappedBy(), s.frameLines(), s.totalFrames(), s.shownFrames(),
                List.of("TimeoutException: gateway read timed out at GatewayClient.call(GatewayClient.java:61)"));
        return new Report(rich.id(), rich.epochMillis(), rich.threadName(), stack,
                rich.messagePattern(), rich.args(), rich.loggerName(), rich.mdc(), rich.fields(),
                rich.captured(), rich.story(), rich.envLine(), rich.occurrences(),
                rich.firstSeenMillis(), repro().repro(), new Provenance(false, "9a2b1c", 1_000_000L, 2));
    }

    /** Every report fixture, for checks that must see each member the renderer can write. */
    static List<Report> all() {
        return List.of(full(), noThrowable(), rich(), repro(), provenance());
    }
}
