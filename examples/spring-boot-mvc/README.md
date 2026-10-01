# Spring Boot MVC Example

A standalone runnable Spring Boot MVC (servlet) application demonstrating zero-configuration `stacktale` integration with `stacktale-spring-boot-starter`.

## What This Example Demonstrates

- **Zero-Config Integration**: Adding `stacktale-spring-boot-starter` automatically configures Logback to capture AI-ready error reports without extra XML configuration.
- **Automatic Package Highlighting**: Packages matching `stacktale.app-packages` are marked with `← YOUR CODE` in stack traces.
- **Context & Story Extraction**: Demonstrates how SLF4J MDC (`traceId`), preceding logger events (request `INFO` and cache miss `WARN`), and exception domain state get captured alongside errors.

## How to Run

From this directory (`examples/spring-boot-mvc`):

```bash
mvn spring-boot:run
```

The application will start on port `8081`.

## How to Trigger the Error

In another terminal, trigger the order confirmation endpoint:

```bash
curl -X POST http://localhost:8081/orders/123/confirm
```

This simulates fetching a customer, encountering a cache miss (`null`), and throwing a `NullPointerException` wrapped in a custom `OrderConfirmationException`. The service wraps and rethrows without logging; `web/ErrorHandler` (a `@RestControllerAdvice`) logs the wrapper once and answers 500. `web/TraceIdFilter` puts a `traceId` in the MDC, which Micrometer Tracing or the OpenTelemetry agent would do in a real app.

## What to Look For

Open the generated report in `errors-ai.log`. This is real output, captured from the commands above:

```
━━━ ERROR #461efec4 ━━━ 2026-10-01 20:31:20.061 thread=http-nio-8081-exec-1 ━━━
NullPointerException: Cannot invoke "com.example.demo.model.Customer.getEmail()" because "customer" is null
at OrderService.confirmOrder(OrderService.java:20) ← YOUR CODE
wrapped by: OrderConfirmationException("confirmation aborted for order 123") at OrderService.confirmOrder(OrderService.java:24)
log: "Failed to confirm order {}" args=[123] logger=c.e.d.w.ErrorHandler
mdc: traceId=bed9304e
fields: failedStep=send-confirmation-email orderId=123 retryable=false

story (traceId=bed9304e, last 5 events, 34ms):
  20:31:20.027 INFO  request       POST /orders/123/confirm
  20:31:20.059 INFO  OrderService  Processing order confirmation for order 123
  20:31:20.059 INFO  OrderService  Fetching customer for order 123
  20:31:20.059 WARN  OrderService  Cache miss for customer on order 123, returning null
  20:31:20.061 ERROR ErrorHandler  Failed to confirm order 123   ← this error

stack (distilled, 3 of 57 frames):
  OrderService.confirmOrder(OrderService.java:20) ← culprit
  OrderController.confirmOrder(OrderController.java:20)
  … 35 collapsed (jdk ×2, spring ×17, servlet ×2, tomcat ×13, other ×1)
  TraceIdFilter.doFilterInternal(TraceIdFilter.java:30)
  … 19 collapsed (spring ×1, tomcat ×17, jdk ×1)

env: app=shop-demo | java 21.0.6 | windows
━━━ END #461efec4 ━━━
```

> **How to log so stacktale shines:** throw rich domain exceptions and log each failure exactly once, at the boundary (`@RestControllerAdvice`), with the exception you actually throw. If you log the cause before wrapping it, the report never sees the wrapper, so it has no `wrapped by:` line and no `fields:`.

### Key Highlights

- **Root Cause First**: The headline is the underlying `NullPointerException`; the wrapper becomes one `wrapped by:` line.
- **`← YOUR CODE` / `← culprit`**: Frame markers highlight lines from your application; 54 Spring, Tomcat and JDK frames collapse into two counted lines.
- **The `story` Section**: The request line (logged by the starter as `request`), the service's `INFO` lines and the cache-miss `WARN`, bound by the MDC `traceId`.
- **Captured Exception Fields**: Getters on `OrderConfirmationException` (`getOrderId()`, `isRetryable()`, `getFailedStep()`) fill the `fields:` line.
- **`env: app=shop-demo`**: Taken from `spring.application.name`.
