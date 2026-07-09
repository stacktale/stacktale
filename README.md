<p align="center">
  <img src="docs/logo.png" width="170" alt="stacktale — a campfire burning stack-frame logs">
</p>

# stacktale

> *Stack traces that tell the tale.*

A Logback appender that turns Java errors into **AI-ready reports**. Add one dependency,
one line of config — and every error your app logs becomes a complete, token-efficient
report in `errors-ai.log`, designed for the reader that actually debugs your code in 2026:
an AI assistant. Your human logs stay exactly as they are.

## Why

The Java error log format was designed in the 90s for a human with `grep`. Today the most
frequent reader of an error log is an AI coding assistant — and the information it needs
most is exactly what the classic format throws away:

- **What happened before the error.** The log lines that explain the failure exist, but
  they're interleaved with 20 other threads, hundreds of lines above the stack trace.
- **The values involved.** `NullPointerException at OrderService.java:87` forces the AI
  to guess. The message args and MDC were right there at log time — and got scattered.
- **The environment.** App version, git commit, Java version, profile: an AI asks for
  these in half of all debugging sessions, because no log line carries them.

So every pasted-log debugging session becomes an interrogation: 5–10 messages of the AI
asking for context that existed at the moment of the error and was thrown away.
stacktale captures that context **at the source** and writes it as one structured block.
Post-processing can't do this — by the time the log is written, the story is gone.

## What the AI sees

This is a real report produced by [`DemoApp`](src/test/java/io/github/gabrielbbaldez/stacktale/DemoApp.java)
— an order flow where a cache miss returns `null` and nobody checks it:

```
━━━ ERROR #6fd4 ━━━ 2026-07-09 16:33:21.098 thread=main ━━━
NullPointerException: Cannot invoke "DemoApp$Customer.email()" because "customer" is null
at DemoApp.confirmOrder(DemoApp.java:58) ← YOUR CODE
log: "Failed to confirm order {}" args=[123] logger=i.g.g.s.d.OrderService
mdc: traceId=9f3a userId=42

story (traceId=9f3a, last 4 events, 430ms):
  16:33:20.668 INFO  OrderController  POST /orders/123/confirm
  16:33:20.786 INFO  CustomerClient   fetching customer 555 → HTTP 404
  16:33:20.786 WARN  CustomerCache    miss for customer 555, returning null
  16:33:21.098 ERROR OrderService     Failed to confirm order 123   ← this error

stack (distilled, 2 of 2 frames):
  DemoApp.confirmOrder(DemoApp.java:58) ← culprit
  DemoApp.main(DemoApp.java:47)

env: app=shop-api 1.4.2 (git 7e3c1f) | java 21.0.6 | windows
━━━ END #6fd4 ━━━
```

Read the `story` section: the root cause — the cache returning `null` on a 404 — is
*right there*, one line above the error. In a traditional log those lines were 300 lines
up, tangled with other threads. An AI (or you) reads this block once and knows what
happened, with what values, in which environment.

Meanwhile your console shows a single extra line:

```
INFO stacktale -- AI error report #6fd4 → ./errors-ai.log
```

## Quickstart

> Not on Maven Central yet (planned for 0.1.0). For now, build from source:
> `git clone https://github.com/GabrielBBaldez/stacktale && cd stacktale && mvn install`

```xml
<dependency>
  <groupId>io.github.gabrielbbaldez</groupId>
  <artifactId>stacktale</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Then add the appender to your `logback.xml`:

```xml
<appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.StacktaleAppender">
  <appPackages>com.your.app</appPackages> <!-- optional but recommended -->
</appender>

<root level="INFO">
  <appender-ref ref="CONSOLE"/>
  <appender-ref ref="STACKTALE"/>
</root>
```

That's it. Errors now produce reports in `./errors-ai.log`. Point your AI assistant at
that file (it announces itself on startup, and the file header explains the format to
any AI that opens it — no docs needed).

Works out of the box with **Spring Boot** (Logback is its default logging backend).

## What gets captured

| Section | What it is |
|---|---|
| headline | The **root cause**, first — wrappers become one `wrapped by:` line each |
| `at` | The culprit frame: first frame of *your* code in the root cause |
| `log` | The message pattern, its args (the values!), and the logger |
| `mdc` | The full MDC at the moment of the error |
| `story` | The last N events from the same request (MDC `traceId`) or thread — the narrative that led to the error |
| `stack` | Distilled stack: framework runs collapse into `… 39 collapsed (spring ×24, tomcat ×11)` |
| `env` | App name/version, git sha, Java version, active profile, OS — collected once |
| repeats | The same error again doesn't dump again: `━ #6fd4 repeated 47× ━` |

Uncaught exceptions (threads dying without any `log.error`) are funneled through the
same pipeline by an optional handler — useful for plain-Java apps.

## Does it actually help? (blind A/B)

We ran a blind test: [`BlindTestScenario`](src/test/java/io/github/gabrielbbaldez/stacktale/BlindTestScenario.java)
simulates a checkout that dies on a total-limit sanity check while 6 other request threads
produce realistic traffic. The true root cause (a stale-price fallback mixing USD prices
into a BRL order after a pricing-service timeout) never appears in the stack trace — only
in the events before the error. The SAME run wrote both a classic interleaved log
(95 lines) and a stacktale report (27 lines). Two fresh AI agents, identical prompts, no
source access, each got one artifact.

Results, honestly reported:

- **Both found the root cause** — 95 interleaved lines still fit in a strong model's
  attention, so accuracy tied on this small scenario.
- **The report needed ~4× less log input** for the same diagnosis, and the agent reading
  it spent no effort separating 7 threads of noise.
- The report reader **inferred blast radius from the format itself** (no `repeated N×`
  lines → single occurrence) — information the classic log doesn't surface.
- The structural argument stands: a classic log grows with traffic (real production logs
  don't fit in any context window, and the "last 200 lines" you'd actually paste often cut
  the crucial WARN); a stacktale report stays ~27 lines per error, with the story already
  attached.

Reproduce it: run `BlindTestScenario` and hand `target/blind-console.log` vs
`target/blind-errors-ai.log` to any AI, with the same question.

## Configuration

Everything is optional, set as appender properties in `logback.xml`:

| Property | Default | What it does |
|---|---|---|
| `file` | `errors-ai.log` | Where reports go |
| `appPackages` | *(heuristic)* | Comma-separated package roots marked `← YOUR CODE` |
| `storySize` | `15` | Events kept per context for the story |
| `storyWindowSeconds` | `60` | Max age of story events |
| `dedupWindowSeconds` | `300` | One full report per error per window |
| `maxFileSizeMb` | `5` | Size-based rotation (keeps one `.1` backup) |
| `installUncaughtHandler` | `true` | Report uncaught exceptions too |
| `reportErrorsWithoutThrowable` | `true` | `log.error(...)` without exception still reports |
| `correlationMdcKeys` | `traceId,correlationId,requestId` | MDC keys that group the story per request |
| `zone` | system | Timezone for report timestamps |

## Guarantees

- **Never breaks your app.** Any internal failure degrades stacktale to a no-op; nothing
  propagates to your code.
- **Cheap happy path.** Non-error events cost one bounded ring-buffer insert. No I/O.
- **Nothing leaves the machine.** stacktale writes a local file, same trust boundary as
  your existing logs. No network, no phone-home.

## Limitations (honest ones)

- The story follows MDC correlation keys, falling back to same-thread. Fully async apps
  **without MDC propagation** get a fragmented story.
- stacktale organizes what your app already logs. If the app logs nothing before the
  error, there is no story to tell.
- Logback only, for now (which includes Spring Boot by default). Log4j2 is on the roadmap.

## Roadmap

- `stacktale-spring-boot-starter` — auto-config + HTTP request line in the story
- Log4j2 appender
- Maven Central publication
- Report format is versioned (`st/1`) and pinned by golden-file tests — the file header
  and [design doc](docs/design.md) are the spec

## License

[Apache-2.0](LICENSE)
