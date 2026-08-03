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

This simulates fetching a customer, encountering a cache miss (`null`), and throwing a `NullPointerException` wrapped in a custom `OrderConfirmationException`.

## What to Look For

Open the generated report in `errors-ai.log`:

```
━━━ ERROR #... ━━━ ... thread=http-nio-8081-exec-1 ━━━
NullPointerException: Cannot invoke "com.example.demo.model.Customer.getEmail()" because "customer" is null
at com.example.demo.service.OrderService.confirmOrder(OrderService.java:20) ← YOUR CODE
wrapped by: OrderConfirmationException("confirmation aborted for order 123") at com.example.demo.service.OrderService.confirmOrder(OrderService.java:23)
log: "Failed to process order confirmation for order {}" args=[123] logger=c.e.d.s.OrderService
mdc: traceId=a1b2c3d4
fields: failedStep=send-confirmation-email orderId=123 retryable=false

story (traceId=a1b2c3d4, last 4 events):
  08:43:00.100 INFO  OrderController  POST /orders/123/confirm
  08:43:00.105 INFO  OrderService     Processing order confirmation for order 123
  08:43:00.106 WARN  OrderService     Cache miss for customer on order 123, returning null
  08:43:00.110 ERROR OrderService     Failed to process order confirmation for order 123   ← this error

stack (distilled):
  com.example.demo.service.OrderService.confirmOrder(OrderService.java:20) ← culprit
  ...
```

### Key Highlights

- **Root Cause First**: Reports start with the underlying `NullPointerException` rather than outer wrapper noise.
- **`← YOUR CODE` / `← culprit`**: Frame markers quickly highlight lines from your application.
- **The `story` Section**: Displays preceding request events (`INFO`, `WARN` cache miss) bound to the MDC `traceId`.
- **Captured Exception Fields**: Domain getters on `OrderConfirmationException` (`getOrderId()`, `isRetryable()`, `getFailedStep()`) automatically populate the `fields:` summary.
