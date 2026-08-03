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

Open the generated report in `errors-ai.log`:

```
━━━ ERROR #... ━━━ ... thread=boundedElastic-1 ━━━
IllegalStateException: pricing feed disconnected
at com.example.webflux.controller.QuoteController.lambda$quote$1(QuoteController.java:27) ← YOUR CODE
log: "quote failed for instrument {}" args=[314] logger=c.e.w.c.QuoteController
mdc: traceId=c3d4e5f6

story (traceId=c3d4e5f6, last 4 events):
  08:50:00.100 INFO  QuoteController  GET /quotes/314
  08:50:00.101 INFO  QuoteController  quote requested for instrument 314
  08:50:00.105 INFO  QuoteController  pricing lookup for instrument 314
  08:50:00.110 ERROR QuoteController  quote failed for instrument 314   ← this error

stack (distilled):
  com.example.webflux.controller.QuoteController.lambda$quote$1(QuoteController.java:27) ← culprit
  ...
```

### Key Highlights

- **Story Across Scheduler Hops**: Log events emitted on different thread pools (`boundedElastic` thread hop #1 and `parallel` thread hop #2) remain grouped in the `story` under the same reactive `traceId`.
- **Root Cause & Culprit Highlight**: Pinpoints the `IllegalStateException` and marks your controller line with `← YOUR CODE`.
//This example currently targets 1.1.0-SNAPSHOT because 
- it depends on the WebFlux auto-configuration fix that 
- has not yet been released to Maven Central.