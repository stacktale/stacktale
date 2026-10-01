# Spring Boot WebFlux Example

A standalone runnable Spring Boot WebFlux (reactive, non-servlet) application demonstrating zero-configuration `stacktale` integration with `stacktale-spring-boot-starter`.

## What This Example Demonstrates

- **Reactive Context Propagation**: Demonstrates how `stacktale`'s WebFlux filter maintains log event context ("story") across multiple Project Reactor scheduler hops (`boundedElastic` and `parallel` threads).
- **Zero-Config WebFlux Integration**: Adding `stacktale-spring-boot-starter` automatically sets up reactive WebFlux context tracking without manual Logback or Reactor configuration.
- **Package Highlighting**: Packages matching `stacktale.app-packages` are flagged with `← YOUR CODE` in distilled stack traces.

## How to Run

From this directory (`examples/spring-boot-webflux`):

```bash
mvn spring-boot:run
```

The application will start on port `8082`.

## How to Trigger the Error

In another terminal, trigger the reactive quote endpoint:

```bash
curl http://localhost:8082/quotes/314
```

This request logs the initial request, schedules a pricing lookup on `boundedElastic()`, shifts to `parallel()`, and throws an `IllegalStateException("pricing feed disconnected")`.

## What to Look For

Open the generated report in `errors-ai.log`. This is real output, captured from the commands above:

```
━━━ ERROR #430478c3 ━━━ 2026-10-01 20:31:25.827 thread=parallel-1 ━━━
IllegalStateException: pricing feed disconnected
at QuoteController.lambda$quote$1(QuoteController.java:28) ← YOUR CODE
log: "quote failed for instrument {}" args=[314] logger=c.e.w.c.QuoteController
mdc: traceId=b5775fbd

story (traceId=b5775fbd, last 4 events, 36ms):
  20:31:25.791 INFO  request          GET /quotes/314
  20:31:25.816 INFO  QuoteController  quote requested for instrument 314
  20:31:25.826 INFO  QuoteController  pricing lookup for instrument 314
  20:31:25.827 ERROR QuoteController  quote failed for instrument 314   ← this error

stack (distilled, 1 of 11 frames):
  QuoteController.lambda$quote$1(QuoteController.java:28) ← culprit
  … 10 collapsed (reactor ×4, other ×1, jdk ×5)

env: app=quote-demo | java 21.0.6 | windows
━━━ END #430478c3 ━━━
━ #430478c3 repeated 2× (last 20:31:25.861) ━
```

### Key Highlights

- **Story Across Scheduler Hops**: The three lines were logged on three threads (`reactor-http-nio`, `boundedElastic`, `parallel`) and stay in one `story` under the same `traceId`. That needs `io.micrometer:context-propagation` on the classpath (see `pom.xml`); WebFlux does not bring it. Without it the traceId does not survive a hop, so the report has no `mdc:` line and the story holds only the error.
- **Root Cause & Culprit Highlight**: Pinpoints the `IllegalStateException` and marks your controller line with `← YOUR CODE`.
- **One report, not two**: The controller logs the error and returns it, so Spring's error handler logs the same exception again on its way out. stacktale recognizes it as the same error and appends `repeated 2×` (the count is cumulative) instead of writing a second report. To keep the console quiet too, log each failure in one place.
- **`env: app=quote-demo`**: Taken from `spring.application.name`.
