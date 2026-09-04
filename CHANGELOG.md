# Changelog

All notable changes to stacktale are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[SemVer](https://semver.org/). The report format (`st/1`) is versioned independently
and pinned by golden-file tests.

## [Unreleased]

### Added

- **MCP: `culprit_source(id, radius)` and `tests_covering(id)` read the working tree.** The
  report says which line failed; the first says what is on it, with the lines around it and the
  culprit marked. Source code is deliberately not in `errors-ai.log` — that file is gitignored,
  redacted, uploaded to CI artifacts and sized for tokens — and it is stale besides: during a
  fix loop only the current source is the right answer. The second reports which test files name
  the culprit's class and method, a name match rather than coverage, and says so in its own
  reply; the answer worth acting on is `none`, which means writing a reproduction test rather
  than hunting for one that does not exist. Both degrade with an explanation when the frame
  names a file the tree does not have, because a failed tool call leaves an agent with nothing
  to do next. (#138)
- **MCP: `repro_for(id)` turns a report's `repro:` seed into a JUnit skeleton.** The seed is the
  one section stacktale writes for a machine — the throw site's fully qualified class, its
  method, the declared parameter types and the values the failing call was given — and until
  now an agent had to transcribe it out of prose, which is where a declared type or an argument
  order goes quietly wrong. TDD-Bench-Java measured agents writing reproduction tests at 4% on
  proprietary code with no hints, rising to 20% once given concrete class names and method
  signatures; this hands over exactly that. Reads both `st/1` and `st-json/1`. A redacted value
  arrives as an explicit `TODO`, never as a literal: `String token = "███"` compiles, reads as
  data, and would reproduce a call that never happened. A report with no seed gets the two
  switches it needs rather than an error. (#135)
- **A guard that keeps the five adapters' configuration surfaces in step.**
  `check-config-parity.sh` reads the README's configuration table and asserts each knob is
  reachable from Logback, Log4j2, JUL, the Spring starter and Quarkus — matching the shape
  that makes it *settable* in each, so a private field nobody can set does not count. Run
  against the commit before #212, it names those three `repro` gaps and nothing else.
  Deliberate absences are listed with their reason: an undocumented gap and a bug are
  otherwise indistinguishable. (#211)

### Changed

- `docs/FORMAT.md` files the `repro:` section under **§3 Report block** and gives it a row in
  that section's field table. It was a subsection of §5 *Non-report entries*, next to
  `repeated`, `app start` and `storm:` — the three things that are not reports. (#216)

### Fixed

- **`st-json/1` dropped the `repro:` seed the text format carries.** FORMAT.md §7 opens its
  correspondence table with "Both formats carry the same information"; `JsonReportRenderer` is
  242 lines and the string `repro` appeared in none of them. An application on `format=json`
  with `repro=true` and the agent attached paid for the capture and got nothing — silently,
  since an absent seed looks exactly like a throw site the agent did not instrument. It is now
  a `repro` member mirroring the record, omitted when there is no seed like `mdc` and `fields`
  already are. The one format an agent is most likely to read was the one dropping the section
  written for a machine. (#216)
- **Every Quarkus report said `env: app=?`.** Quarkus knows the application name and version —
  `quarkus.application.*`, defaulted from the artifact's coordinates — and the extension never
  asked, so `EnvCollector` fell through to its two fallbacks: a Spring Boot artifact
  (`build-info.properties`) and a system property nobody sets. The values now come from
  `ApplicationInfoBuildItem` at build time, which is where Quarkus fixes them, and travel to
  the recorder beside the deduced app packages. Which service is on fire is the first thing on
  that line, and it is the field that matters when several services' reports land in one place
  for an agent to read. (#213)
- **`repro` could not be turned on from Logback, the Spring Boot starter or Quarkus.** The
  configuration table offers it "as appender properties in `logback.xml`, or `stacktale.*` in
  `application.yml`", and only Log4j2 and JUL ever read it: the Logback appender held the
  field and passed it to the builder but had no setter, the starter drives that appender and
  had no property, and the Quarkus mapping had no method. All three fail silently — Joran logs
  `Ignoring unknown property [repro]`, `@ConfigurationProperties` drops unknown fields, Quarkus
  warns and starts — so the knob looked set and the `repro:` section simply never appeared.
  (#210)

### Changed

- The tests that guard those three surfaces now fail when a knob stops arriving, rather than
  when it stops binding. `everyPropertyReachesTheAppender` asserted on the properties bean, so
  a value that bound and was then never passed to the appender read as covered — which is the
  exact shape of the bug above. `LogbackXmlConfigTest` also picks up `appName` and `appVersion`,
  settable since #150 and named in no test in the module. (#210)

## [1.3.1] — 2026-09-03

### Fixed

- **`stacktale-quarkus` did not work on any Quarkus newer than the 3.15 it shipped against.**
  The build step took the runtime `@ConfigMapping` as a parameter, and Quarkus now refuses
  that outright — a build step runs at augmentation time, when a run-time value does not
  exist yet — so `StacktaleProcessor` failed to load and the application did not start. The
  recorder receives the configuration as a `RuntimeValue` through its constructor instead,
  which is what the framework's own error message asks for. Verified against 3.15.1, 3.20.0
  and 3.39.1: the new shape works on all three, so the floor does not move. (#193)

### Changed

- **Every published JPMS module name is checked against the jar that ships it.** The README
  documented ten and CI verified two — the other eight, including both halves of
  `stacktale-quarkus` published in 1.3.0, rested on a hand-written pom property. A name is a
  `requires` directive in someone else's build, so a typo is not fixable after release.
  `check-module-names.sh` compares the pom property, the manifest of the built jar and the
  README row, and the README no longer claims more coverage than it has. (#203)
- The compatibility matrix gains a **Quarkus 3.15 (floor)** leg, and the README's
  compatibility table a Quarkus row — checked by `check-readme-compat.sh`, which had no way
  to read a property the root pom does not define and now takes a module. Without it the
  row would have been a claim nobody verified. (#188)

[1.3.1]: https://github.com/stacktale/stacktale/releases/tag/v1.3.1

## [1.3.0] — 2026-08-20

Two headline additions. `repro:` turns the top of a report into the call that failed — the
fully-qualified signature and the argument values it was given — so a reader starts with
something to re-run instead of a stack to reconstruct one from; it is off by default and
stays that way deliberately, being the one section that renders values against a named
signature. And `stacktale-quarkus` makes Quarkus the fourth stack that reports with nothing
to configure.

### Added

- **`repro:` — a reproduction seed in the report.** With `stacktale-agent` attached and
  `repro=true`, a report gains the throw site as a call you can copy:

  ```
  repro (throw site, via stacktale-agent):
    com.acme.shop.PaymentService#charge(long orderId, java.math.BigDecimal amount)
      orderId = 889
      amount = 149.90
    throws IllegalStateException: payment gateway refused
  ```

  Parameter types are the declared ones whenever reflection can resolve the method, so the
  signature is the real one rather than one inferred from the runtime classes of the
  arguments — `long` stays `long` instead of becoming `java.lang.Long`. Where the method
  can't be resolved unambiguously (overloads with the same arity), it falls back to the
  runtime class, which still names something a call can be written against. Only the
  innermost frame becomes the seed: a seed naming the outer caller would describe a call
  that did not fail. **Off by default**, and worth leaving off for most projects — it is the
  only section that renders argument values against a named signature, which is a larger
  privacy surface than the rest of a report combined. Values go through the same redaction as everything else, and
  the name and value are cleaned as one string so a secret-named parameter masks its value.
  An older agent paired with a newer core loses the seed and keeps `captured:`, rather than
  failing. Additive under the FORMAT §6 rules, so `st/1` does not change version. (#135)
- **`stacktale-quarkus`** — a Quarkus extension, so a Quarkus app gets reports by adding the
  dependency, with no `logging.properties` to edit. Quarkus logs through the JBoss LogManager,
  which is a `java.util.logging` implementation, so the extension reuses the `stacktale-jul`
  handler and adds only the Quarkus wiring: a runtime/deployment split that decides the
  configuration at build time and stays native-image friendly, `stacktale.*` keys through
  `@ConfigMapping`, and a `@ServerRequestFilter` that opens each request's story with its HTTP
  line — the counterpart of the Spring starter's servlet and WebFlux filters. Thanks
  @DenizAltunkapan. (#82)
- **Reproducible builds.** Two clean builds of the same commit now produce byte-identical
  jars, so a tag can be rebuilt and checked against what is on Maven Central. CI packages
  twice and diffs the checksums on every PR. (#178)

### Fixed

- **A failed storm-line write silently swallowed the next occurrence of that error.** The
  dedup entry was recorded before the line reached disk, so when the write failed the error
  counted as already-reported: the next occurrence was suppressed, and the one that would
  have surfaced the problem never appeared. The decision is now rolled back when the write
  fails, and the following occurrence reports normally. (#180)
- **The report action produced an empty summary for `st-json/1` logs.** It parsed only the
  text format, so a project writing NDJSON got a job summary that said nothing was wrong.
  (#167)
- **The release workflow could tag a commit that existed nowhere.** The tag was pushed
  before the branch carrying the commit it pointed at, leaving a tag resolving to an object
  the remote did not have. Branch and tag are now separate, ordered steps. (#153)

### Documentation

- `docs/FORMAT.md` gains the `seen:` and story-omitted lines that the grammar had always
  been able to emit but never spelled out (#170), and a table pinning every `st-json/1`
  member whose name differs from its `st/1` counterpart, so the mapping never has to be
  inferred from an example (#172).
- `stacktale-junit` joins the JPMS module table. (#171)

Contributions this cycle from **[@dchaudhari7177](https://github.com/dchaudhari7177)** (the
link checker #179, reproducible builds #178, the dedup rollback #180, and the CodeQL,
examples-pinning and plugin-manifest guards), **[@DenizAltunkapan](https://github.com/DenizAltunkapan)**
(the Quarkus extension #187), **[@dongyikuan919](https://github.com/dongyikuan919)** (widening
the link check to every Markdown file #184), **[@Sarthak-Vatsa](https://github.com/Sarthak-Vatsa)**
(the JUnit Platform 1.10 floor #164) and **[@syf2211](https://github.com/syf2211)**
(`st-json/1` summaries in the report action #167). Thank you.

[1.3.0]: https://github.com/stacktale/stacktale/releases/tag/v1.3.0

## [1.2.0] — 2026-08-03

A bug-fix release, and the bugs are the kind that hide: every one of them left the library
looking like it worked. A wedged logging thread, a rotation that stops forever after one
crash, an uncaught-exception path that recursed until the stack ran out, a Log4j2 filter
that silently did nothing, seven JUL settings read by nobody, and a repeat counter that
ended up wrong rather than absent.

### Added

- **`StacktaleExtension`** for `stacktale-junit` — optional, registered with `@ExtendWith`
  or JUnit's extension autodetection. A test that sets a correlation key used to get a
  report whose story was one line: itself. The listener is notified after the test method
  returns, when the MDC is already unwound, so the failure event looked in the thread
  bucket while everything the test logged went to the `traceId` one. The extension runs
  inside the test's lifecycle and snapshots the MDC while it still exists. The zero-config
  listener behaves exactly as before without it. (#134)
- **`examples/`** — three runnable projects, one per entry point: plain Java with JUL,
  Spring Boot MVC, Spring Boot WebFlux including the Reactor scheduler-hop case. Built in
  CI on every change so they cannot drift from the library. (#62)

### Fixed

- **A custom `redactPattern` could wedge the application's logging thread.** Redaction runs
  synchronously inside `log.error(...)`, and the existing `catch (Throwable)` does not help
  because a hang is not a throw. Custom patterns now match through a `CharSequence` that
  enforces a deadline from `charAt` — the only hook into a match already under way, since
  `Matcher` has no timeout — with a 100ms budget shared by all custom rules. Measured on a
  backreference pattern: 30 characters took **30.6 seconds** before. (#118)
- **An orphaned `.rotating` file disabled rotation permanently.** A process killed between
  moving the live file aside and rolling it into `.1` left the sibling behind, and every
  later rotation failed with `FileAlreadyExists` — for that run and every run afterwards,
  so `errors-ai.log` grew without bound and `maxFileSizeMb` meant nothing. The writer now
  completes the interrupted rotation on construction, folding the orphan in rather than
  deleting it: it holds the newest reports written before the crash. (#120)
- **Uncaught exceptions recursed until the stack ran out.** With no previous handler — the
  ordinary case, since most applications never set one — the fallback handed the throwable
  to the thread's `ThreadGroup`, whose root ends by calling the default handler, which is
  ours. Every uncaught exception died as a `StackOverflowError` instead of being reported.
  Separately, `install()` now replaces a stale handler and `uninstall()` runs from every
  adapter's stop, so a DevTools restart no longer feeds a closed pipeline or pins the dead
  `LoggerContext`. (#121)
- **The Log4j2 appender ignored a nested `<Filters>` element.** The builder declared no
  `@PluginElement Filter` and the appender was constructed with a hard-coded null one, so
  the idiomatic configuration did nothing — silently, and only on this backend. Logback and
  JUL both honoured it. (#122)
- **Seven documented `logging.properties` keys were read by nobody** on `stacktale-jul`:
  `zone`, `captureExceptionFields`, `truncateOnStart`, `reportErrorsWithoutThrowable`,
  `echoSuppressionMillis`, `containerLoggers` and `emitReportsToLogger`.
  `captureExceptionFields` is a privacy control — it decides whether getters on the user's
  own exception types run and land in the report — so a JUL user whose exceptions carry PII
  had no way to turn it off. An invalid `zone` now keeps the system default and says so
  rather than silently shifting every timestamp. (#124)
- **The trailing repeat counters were lost on plain Logback.** `close()` drains them and
  runs from `stop()`, which Logback calls only on `LoggerContext.stop()` — and Logback
  registers no shutdown hook unless `<shutdownHook/>` is configured. Fifty identical errors
  left `repeated 2×` as the last word in the file: not missing but stale, which reads as
  real. The appender registers its own hook; no configuration needed. (#127)
- **`StoryBuffer` took a global monitor on every event**, and its published benchmark was
  single-threaded. Now a `ConcurrentHashMap` with sample-based LRU eviction, keeping the
  guarantee that matters: a request still logging its way to a failure does not lose its
  story to another request's traffic. Thanks @Shubh2-0. (#125)
- **The Log4j2 adapter allocated a map per event.** `ReadOnlyStringMap.toMap()` copies
  unconditionally, empty ThreadContext or not, so every application that never touches it
  paid for a map on the happy path — which the README's ~110 ns figure, measured on
  Logback, did not include. (#123)

### Changed

- **A Log4j2 `<Stacktale>` element now requires a `name`.** The builder extends
  `AbstractAppender.Builder` to pick up the `Filter` element, and the inherited `name` is
  required where the local one defaulted to `STACKTALE`. Every documented example sets one,
  and an unnamed appender cannot be referenced from `<AppenderRef>`, so it was never usable
  in a real configuration — but a hand-written config relying on the default will now fail
  to start rather than start wrong. (#122)
- `containerLoggers` is configurable on JUL, which the README previously said it was not.

[1.2.0]: https://github.com/stacktale/stacktale/releases/tag/v1.2.0

## [1.1.0] — 2026-07-29

The release that came out of using the library on a real app and auditing the rest. Most of
what changed is the difference between a report that is technically correct and one you can
act on: a path you can open, a frame you wrote, an identity that survives you editing the
file — and a failing test finally producing a report at all.

### Added

- **`stacktale-junit`** — a JUnit Platform listener, discovered through `META-INF/services`,
  that turns a failing test into an `st/1` report. One test-scoped dependency, no
  configuration. When an adapter is already running it reports through that pipeline, so a
  test failure carries the story the code logged on its way down. Closes the gap that left
  the MCP fix-loop blind to a red build.
- **A Claude Code plugin** — the repo doubles as its own marketplace
  (`/plugin marketplace add stacktale/stacktale`), bundling the MCP server and a skill that
  knows how to run the fix-loop.
- **A GitHub Action** (`.github/actions/report`) that posts a red build's reports as a
  pull-request comment and a job summary, editing one comment in place rather than
  appending on every push.

### Fixed

- The report **id no longer changes when the file is edited**. The fingerprint hashed the
  culprit frame including its source line, so an agent adding a guard clause above the
  throw site made the same unfixed error mint a new id — and `errors_since_last_check`
  called it new rather than still occurring. Two throw sites in one method now share an id;
  see [FORMAT.md](docs/FORMAT.md) §3.
- **`correlationMdcKeys` now includes `trace_id`**, the key the OpenTelemetry Java agent
  injects. Micrometer spells it `traceId`, so OTel-instrumented apps were silently falling
  back to per-thread stories.
- **Unnamed threads no longer share one story bucket.** Every unnamed virtual thread was
  filed under a single key, so a report could carry another request's log lines. Reachable
  through the JUL, Log4j2 and JUnit paths; Logback synthesizes a name and was unaffected.
- **An unwritable destination now fails loudly.** Nothing touched the filesystem until the
  first error, so a read-only container root produced a pipeline that announced itself
  active and wrote nothing — then re-rendered every error forever. The writer probes its
  destination at construction, and the pipeline parks after five consecutive failures.
- **The root cause message is capped** at 4096 chars with the dropped count stated. An
  unbounded message could blow past `maxFileSizeMb` in a single block, rotate the file's
  history away, and OOM the host.
- **The console line prints an absolute path.** It printed the configured value —
  `errors-ai.log` — which resolves against the JVM's working directory, something a reader
  of that line has no way to know. The startup line also now names `emitReportsToLogger`
  when it is off, since that is the setting people are looking for when they want the report
  in their own log rather than in a file they have to find.
- **The culprit is no longer a Spring proxy.** A CGLIB proxy sits in the application's own
  package, so it was picked as the first app frame while having no source at all —
  `Svc$$SpringCGLIB$$0.getQuote(<generated>:-1)`. The distiller now prefers the first app
  frame with real source, and strips proxy suffixes for display.
- **`env: app=` is filled in on Spring Boot** from `spring.application.name` when
  `build-info.properties` is absent, which it usually is. Thanks @kushalvachar2006.
- **The Spring servlet filter moved behind a class-level condition.** A condition on the
  bean method is evaluated too late to stop `jakarta.servlet.Filter` from being resolved,
  which is the shape that breaks a reactive app. Reported while building the WebFlux
  example.

### Thanks

@kushalvachar2006 (`spring.application.name`), @Ravindra-Pagidala (Codecov badge),
@dchaudhari7177 (recursive-frame collapsing, README compatibility guard), @adity982
(discovery docs), and @asmitayush3021 for finding the reactive auto-configuration problem
while building the examples.

## [1.0.0] — 2026-07-21

**1.0.** The `st/1` report format and the core pipeline are now a stable, committed
contract — proven, not just intended.

- **Stability commitment.** `st/1` is a public API pinned by golden-file tests. From 1.0,
  a breaking format change bumps the format version *and* the major. All five logging
  backends — Logback, Log4j2, `java.util.logging`, `System.Logger`, and the Spring Boot
  starter — write the same format.
- **Proven, not just designed.** Mutation testing on the core (PIT, test strength ≈ 85%)
  confirms the tests actually *catch* regressions in the tricky paths — dedup windows,
  stack distilling, redaction ordering — not merely execute them (#36). A one-hour memory
  soak of **8 million events** confirms the pipeline's bounded state (dedup, story and
  per-thread maps) stays flat under sustained churn — a leak-free ~7.4 MB live set the
  whole run (#37, `docs/soak.md`).
- **Recursive stacks.** A `StackOverflowError` now names the recursion
  (`… N recursive frames (a → b → a)`) instead of printing a wall of identical frames (#105).
- **Docs & guards.** A "point your assistant at the report" quickstart (#100), and a CI
  check that keeps the README compatibility table honest against what the build actually
  tests (#106).

What 1.0 is: seven modules, five logging backends, an MCP server, an optional Java agent,
GraalVM native-image and JPMS support, correlation-preserving redaction on by default, a
JSON output variant, and a text format an AI reads in a fraction of the tokens a raw log
costs — all pinned by a conformance suite and now under a stability guarantee.

### Thanks

Contributions this cycle from **[@dchaudhari7177](https://github.com/dchaudhari7177)** (the
recursion collapse #105 and the compatibility-table guard #106) and
**[@adity982](https://github.com/adity982)** (assistant-discovery docs #100). Thank you.

[1.1.0]: https://github.com/stacktale/stacktale/releases/tag/v1.1.0
[1.0.0]: https://github.com/stacktale/stacktale/releases/tag/v1.0.0

## [0.5.0] — 2026-07-20

Any framework, or none. New backends, a machine-readable format, and a durability pass on
the road to 1.0.

### ⚠️ Upgrading from 0.4.x

The Logback appender moved into its own package for JPMS. If you register it in
`logback.xml`, change the class from
`io.github.gabrielbbaldez.stacktale.StacktaleAppender` to
`io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender`. The Spring Boot starter and
the Log4j2 / JUL handlers are unaffected.

### Added

- **`stacktale-jul`** (new module): a `java.util.logging` / `System.Logger` handler — the
  same `st/1` reports with neither SLF4J nor Logback on the classpath. "Any framework, or
  none" is now literal (#49), with the same optional uncaught-exception funnel as the other
  backends (#55).
- **JSON output** (`st-json/1`): `format=json` writes NDJSON — one addressable JSON object
  per entry, for parsers, pipelines and dashboards rather than an LLM reading raw text. The
  text format stays the default (denser per token) (#50).
- **SLF4J 2.0 key-values**: `log.atError().addKeyValue("orderId", 889)…` is captured into
  the report context and redacted just like MDC (#93).
- **Correlation-preserving redaction** (opt-in): a masked value can carry a stable keyed
  suffix `███(a1b2)`, so an AI can tell whether the *same* secret keeps recurring without
  the value ever being exposed — a one-way, per-process HMAC (#48).
- **JPMS**: every jar declares a stable `Automatic-Module-Name` and works on the module
  path; a resolution smoke test in CI pins it (#44).
- **GraalVM native-image**: reachability metadata plus a `docs/native.md` guide; field
  reflection degrades to empty under a closed configuration instead of failing (#45).
- **OpenTelemetry coexistence**: the agent runs cleanly behind the OTel javaagent, with an
  integration test that loads both and confirms captures still fire (#46).
- **MCP**: a `find_similar_errors` tool ranks past reports by root type and digit-normalized
  message (#67); the file watcher debounces bursts (#66); a two-minute JBang setup path
  (#69).
- **Docs & community**: `SECURITY.md` with a threat model (#94), a `CODE_OF_CONDUCT.md`
  (#102), a pull-request template (#103), an FAQ (#99), a Kotlin quickstart (#90), Gradle
  snippets (#91), and an expanded configuration reference (#72).

### Changed

- **Log4j2 2.26**, **JUnit 6**, and a dependency refresh via Dependabot.
- The story's per-thread fallback is now a bounded LRU keyed by thread *name*, replacing an
  unbounded `ThreadLocal` that could retain context on pooled threads (#52).

### Fixed

- **Durability & concurrency hardening** (#57): a rotation blocked by a reader holding the
  file (Windows) now degrades to appending past the cap and retries, instead of silently
  disabling reporting; no write truncates, so a stray second writer can't wipe another
  process's data; `Deduper.rollback` keeps the session recurrence count; the storm counter
  clears only after a durable write.
- **JSON renderer** no longer leaked a secret-named field's value (#53).
- **Log4j2** now honors configured `containerLoggers` for echo suppression (#54).
- **Spring starter**: the request-line logger no longer leaks into the story of later,
  unrelated errors (#56).
- **Dedup**: a report awaiting a durable write stays silent rather than emitting a duplicate
  or an orphan summary, and a rolled-back window re-arms cleanly (#51).

### Thanks

First contributions to stacktale from **[@Abdul-Rafy2005](https://github.com/Abdul-Rafy2005)**
(configuration reference #72, Gradle snippets #91),
**[@ANONYMOUSZED-beep](https://github.com/ANONYMOUSZED-beep)** (Kotlin quickstart #90), and
**[@Klopez851](https://github.com/Klopez851)** (the FAQ #99). Thank you — the docs read
better because of you.

[0.5.0]: https://github.com/stacktale/stacktale/releases/tag/v0.5.0

## [0.4.0] — 2026-07-10

Production hardening and the agentic loop.

- **MCP push notifications**: the server exposes the report file as an MCP resource with
  subscribe support — your AI assistant is notified the moment a new error lands (file
  watcher → `notifications/resources/updated`) instead of polling. `STACKTALE_FILE` env
  var as an alternative to `--file`; full per-client setup in `docs/mcp-setup.md`.
- **Error-storm control**: `maxReportsPerMinute` (0 = off) caps full reports; a cascade
  of *distinct* errors becomes a throttled `storm: N suppressed` line instead of flooding
  the file and rotating history away when you need it most.
- **Agent filters**: `-javaagent` args gained `excludes=`, `maxFrames=`,
  `maxValueLength=`, and `renderToString=false` — a privacy mode that records an object's
  type and nullness but never its `toString()`.
- **Reactive (WebFlux)**: a reactive filter opens the story with the request line and
  propagates the trace id across scheduler hops via Reactor context propagation.
- **Formal `st/1` specification** (`docs/FORMAT.md`) — the normative format spec, now that
  external contributors and the MCP server parse it. The golden files are its conformance
  suite.
- **Compatibility matrix** in CI (weekly + on POM changes): Logback 1.4/1.5, Log4j2 2.20,
  Spring Boot 3.2/3.3/3.5 — supported ranges documented and each backed by a passing build.
- **One-click release** workflow (`workflow_dispatch`).
- Container-echo suppression and burst-counter flush (from real-world dogfooding); Log4j2
  non-parameterized message types; non-English redaction keywords.

[0.4.0]: https://github.com/stacktale/stacktale/releases/tag/v0.4.0

## [0.3.1] — 2026-07-10

Patch release — fixes a critical agent startup bug in 0.3.0. **If you use
`stacktale-agent`, upgrade.** (The core, logback, log4j2, starter and mcp artifacts are
functionally unchanged from 0.3.0; only bug fixes.)

- **CRITICAL (agent)**: `stacktale-agent-0.3.0` aborts the JVM at startup when attached as
  documented — its manifest declared `Can-Retransform-Classes=false` while the code
  requested retransformation, and `premain` didn't guard against it. Fixed: the manifest
  allows retransform, installation degrades gracefully when the runtime doesn't support
  it, and `premain` disables the agent instead of propagating. A packaging integration
  test now guards the manifest contract.
- **Agent**: package matching now respects package boundaries (`packages=com.a.orders`
  no longer instruments `com.a.ordersprocessing`).
- **Pipeline**: a failing report shipper (`emitReportsToLogger`) no longer rolls back
  dedup state after the report was already written (which duplicated the next report).
- **WebFlux filter**: guarded against `MDC.put` throwing on a partial SLF4J binding — it
  can no longer fail the HTTP request.
- **MCP**: scans all contiguous rotated backups (not just `.1`–`.9`); unknown methods
  return JSON-RPC `-32601`.
- **Dedup**: repeat counts are marked written only after a successful append.

[0.3.1]: https://github.com/stacktale/stacktale/releases/tag/v0.3.1

## [0.3.0] — 2026-07-10

The "capture everything, everywhere" release — closes the entire original backlog.

- **`stacktale-agent`** (new module): optional `-javaagent` that records **method
  arguments at the throw site** into a `captured:` report section —
  `confirmOrder(orderId=889, customer=null)` appears even when the code logged nothing.
  Zero happy-path overhead (advice runs only on exceptional exit), bounded, redacted,
  real parameter names with `-parameters`.
- **`stacktale-mcp`** (new module): read-only MCP server — AI assistants query reports
  as tools (`list_errors`, `get_report`, `errors_since`) instead of reading files.
- **Reactive story (WebFlux)**: a reactive filter opens the story with the request line
  and plants the traceId in the Reactor Context; automatic context propagation keeps the
  story whole across `flatMap`s and scheduler hops.
- **Container-echo suppression**: Tomcat/Spring re-logs of a failure the same thread just
  reported are skipped (configurable window; apps that don't log first keep their
  container report). Found by real-world dogfooding.
- **Burst counter flush**: repeat counts silenced by the summary throttle are written on
  shutdown — the file never understates a burst.
- **Report shipping**: `emitReportsToLogger=true` re-emits each block as ONE event via
  logger `stacktale.reports` for existing log shippers.
- **Redaction**: non-English secret keywords (senha, contraseña, passwort, chave…).
- **Log4j2**: non-parameterized Message types (MapMessage & co.) render readable `log:` lines.
- README: measured token economics — 98.3% session savings (60×), 80.6% per error.

[0.3.0]: https://github.com/stacktale/stacktale/releases/tag/v0.3.0

## [0.2.0] — 2026-07-10

First release on **Maven Central**.

- **Log4j2 support**: new `stacktale-log4j2` module — same pipeline, same st/1 format,
  story correlation via `ThreadContext`, XML plugin appender (`<Stacktale …/>`).
- **`stacktale-core`**: the report pipeline is now framework-agnostic; the Logback
  artifact keeps its coordinates and behavior as a thin adapter.
- **Redaction hardening** (cross-audit findings): name-based redaction now reaches
  `fields:`, `mdc:` and `args=` (secret arg positions derived from the message pattern);
  short Basic credentials and JSON-quoted keys (`"password":"…"`) are masked; secret
  keywords now include non-English names (senha, contraseña, passwort, chave…).
- Spring starter: the auto-configured appender is replaced (never reused stale) across
  application contexts in the same JVM, and detaches on context close.
- `stacktale active` is no longer announced when the pipeline degraded to no-op.
- FieldExtractor reads public getters on package-private exception classes
  (`trySetAccessible`, degrading quietly under closed JPMS modules).
- Log4j2 adapter drops the trailing throwable from `args=` (Log4j2 keeps it inside
  `Message.getParameters()` after extraction).
- Dependency refresh via Dependabot (Logback 1.5.38, AssertJ 3.27.7, Surefire 3.5.6,
  JaCoCo 0.8.15, actions/checkout v7, setup-java v5).

[0.2.0]: https://github.com/stacktale/stacktale/releases/tag/v0.2.0

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

[0.1.0]: https://github.com/stacktale/stacktale/releases/tag/v0.1.0
