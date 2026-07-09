# Changelog

All notable changes to stacktale are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[SemVer](https://semver.org/). The report format (`st/1`) is versioned independently
and pinned by golden-file tests.

## [0.1.0] — 2026-07-09

First release. Everything below is new.

### The library (`stacktale`)

- **`StacktaleAppender`** for Logback: intercepts `ERROR` events and writes complete,
  AI-oriented reports to a separate `errors-ai.log` — the human log stays untouched
  (one pointer line links the two).
- **`st/1` report format**, self-describing (the file header teaches it to any reader)
  and pinned by golden-file tests: root-cause-first headline, `← YOUR CODE` culprit
  frame, log args, MDC, exception `fields:`, the story, a distilled stack, environment.
- **Story**: ring buffers of recent events per MDC correlation key (`traceId`, …) with
  per-thread fallback — the narrative that led to the error, attached to the report.
- **Exception fields**: public getters/fields across the whole cause chain read into
  `fields:` (`orderId=123 retryable=false`) with hard safety caps; the state classic
  formats throw away.
- **Stack distilling**: framework frame runs collapse with counts
  (`… 39 collapsed (spring ×24, tomcat ×11)`); wrappers shrink to one line each.
- **Dedup**: one full report per error fingerprint per window; repeats become throttled
  `repeated N×` lines. Fingerprints are 32-bit (birthday-safe for the dedup map size).
- **Redaction on by default**: JWTs, bearer/basic tokens, secret key=value pairs, long
  hex, emails, Luhn-valid card numbers; extra patterns configurable.
- **Session markers** (`─── app start … ───`) separate application runs;
  `truncateOnStart` for dev loops; rotation with configurable backup depth.
- **Uncaught exception handler** (optional, on by default) funnels thread deaths through
  the same pipeline.
- **`StacktaleExecutors`**: MDC-propagating wrappers so the story survives async hops
  and virtual threads.
- **Never-throw guarantee**: hostile input (poisonous `toString()`, malformed metadata
  files, invalid config, full disk) degrades stacktale, never the host app.
- Performance (JMH): ~110 ns per happy-path event over the Logback baseline; 3.9 µs per
  deduplicated repeat error.

### Spring Boot starter (`stacktale-spring-boot-starter`)

- Zero-config auto-registration on Logback's root logger; `stacktale.*` properties.
- `appPackages` deduced from the `@SpringBootApplication` package.
- Servlet filter opens every story with the HTTP request line through a stacktale-only
  logger (additivity off — the console never sees it); 5xx responses close the story
  with status and duration.

### Validation

- 72 tests (unit, golden-file, integration, concurrency, hostile-input, virtual
  threads, real embedded-Tomcat starter test), line coverage 93%+.
- Blind A/B on AI agents documented in the README.

[0.1.0]: https://github.com/GabrielBBaldez/stacktale/releases/tag/v0.1.0
