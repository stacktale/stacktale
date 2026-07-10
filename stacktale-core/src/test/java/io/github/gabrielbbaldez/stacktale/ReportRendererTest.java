package io.github.gabrielbbaldez.stacktale;


import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportRendererTest {

    private String golden(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/golden/" + name), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @Test
    void fullReportMatchesGolden() throws Exception {
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
        Report r = new Report("a1b2", 1_000_412L, "http-nio-8080-exec-3", stack,
                "Failed to confirm order {}", new Object[]{123}, "com.acme.shop.OrderService",
                Map.of("traceId", "9f3a", "userId", "42"), Map.of(), java.util.List.of(), story,
                "app=shop-api 1.4.2 (git 7e3c1f) | java 21 | profile=dev | linux", 1, 0L);

        String rendered = new ReportRenderer(ZoneOffset.UTC).render(r);

        assertThat(rendered).isEqualTo(golden("full-report.txt"));
    }

    @Test
    void noThrowableReportMatchesGolden() throws Exception {
        Story story = new Story(List.of(
                new StoryEntry(2_000_000L, "ERROR", "PaymentService", "payment rejected for order 77")
        ), "thread main");
        Report r = new Report("beef", 2_000_000L, "main", null,
                "payment rejected for order {}", new Object[]{77}, "com.acme.PaymentService",
                Map.of(), Map.of(), java.util.List.of(), story, "app=? | java 21 | linux", 1, 0L);

        String rendered = new ReportRenderer(ZoneOffset.UTC).render(r);

        assertThat(rendered).isEqualTo(golden("no-throwable.txt"));
    }

    @Test
    void richReportMatchesGolden() throws Exception {
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
        Report r = new Report("a1b2c3d4", 1_000_412L, "http-nio-8080-exec-2", stack,
                "charge failed for order {}", new Object[]{889}, "com.acme.shop.PaymentService",
                Map.of("traceId", "7c2e"), Map.of("orderId", "889", "retryable", "false"),
                List.of("PaymentService.charge(orderId=889, amount=149.90)"),
                story, "app=shop-api 1.4.2 (git 7e3c1f) | java 21 | profile=prod | linux",
                3, 1_000_000L);
        String rendered = new ReportRenderer(ZoneOffset.UTC).render(r);
        assertThat(rendered).isEqualTo(golden("rich-report.txt"));
    }

    @Test
    void recurrenceLineShownOnlyWhenErrorIsNotNew() {
        Story story = new Story(List.of(), "thread main");
        // first occurrence → no seen line (absence means new)
        Report first = new Report("cafe", 2_000_000L, "main", null, "boom", null, "com.acme.Svc",
                Map.of(), Map.of(), List.of(), story, "app=? | java 21 | linux", 1, 0L);
        assertThat(new ReportRenderer(ZoneOffset.UTC).render(first)).doesNotContain("seen:");
        // recurring → the line tells the reader it's systemic and since when
        Report recurring = new Report("cafe", 2_000_000L, "main", null, "boom", null, "com.acme.Svc",
                Map.of(), Map.of(), List.of(), story, "app=? | java 21 | linux", 5, 1_000_000L);
        assertThat(new ReportRenderer(ZoneOffset.UTC).render(recurring))
                .contains("seen: 5× this session, first at 00:16:40.000");
    }

    @Test
    void fieldsLineRenderedSortedAndFlattened() {
        Report r = new Report("cafe", 1_000_000L, "main", null,
                "boom", null, "com.acme.Svc", Map.of(),
                Map.of("orderId", "889", "note", "with\nnewline"), List.of(),
                new Story(List.of(), "thread main"), "app=? | java 21 | linux", 1, 0L);
        String rendered = new ReportRenderer(ZoneOffset.UTC).render(r);
        assertThat(rendered).contains("fields: note=with\\nnewline orderId=889");
    }

    @Test
    void summaryLine() {
        String s = new ReportRenderer(ZoneOffset.UTC).renderSummary("a1b2", 47, 1_000_000L);
        assertThat(s).isEqualTo("━ #a1b2 repeated 47× (last 00:16:40.000) ━\n");
    }

    @Test
    void fileHeaderMentionsFormatAndDelimiters() {
        String h = new ReportRenderer(ZoneOffset.UTC).fileHeader();
        assertThat(h).contains("format st/1").contains("━━━ ERROR #").contains("END #");
    }
}
