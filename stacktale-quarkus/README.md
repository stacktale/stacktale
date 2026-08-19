# stacktale-quarkus

A [Quarkus](https://quarkus.io) extension giving Quarkus apps **zero-config**
[stacktale](https://github.com/stacktale/stacktale) reports: add the dependency and every logged
error becomes a complete, token-efficient `st/1` report in `errors-ai.log` — no
`logging.properties` or `logback.xml` editing.

> Implements [#82](https://github.com/stacktale/stacktale/issues/82).

## Usage

```xml
<dependency>
  <groupId>io.github.gabrielbbaldez</groupId>
  <artifactId>stacktale-quarkus</artifactId>
  <version>1.3.0-SNAPSHOT</version>
</dependency>
```

Optionally tune it in `application.properties` (all keys have sensible defaults):

```properties
stacktale.app-packages=com.your.app   # deduced automatically if omitted
stacktale.file=errors-ai.log
stacktale.format=text                  # or: json (st-json/1 NDJSON)
stacktale.request-logging=true
```

## How it works

Quarkus logs through the JBoss LogManager, a `java.util.logging` implementation — so this
extension reuses the existing **`stacktale-jul`** handler and only adds the Quarkus glue:

| Piece | File | Role |
|---|---|---|
| Config | `StacktaleConfig` | `stacktale.*` via `@ConfigMapping` |
| Recorder | `StacktaleRecorder` | attaches the JUL handler at **startup** (`@Recorder`) |
| Request filter | `StacktaleRequestFilter` | opens each story with the HTTP request line (`@ServerRequestFilter`) |
| Build steps | `StacktaleProcessor` | records the recorder, deduces app packages, registers native hints (**build time**) |

The `runtime` + `deployment` split and the `@BuildStep`/`@Recorder` pair make the extension
GraalVM-native-friendly: all wiring is decided at build time, nothing is scanned at boot.

## Build & test

```bash
# from the repo root, with core modules installed:
mvn -pl stacktale-quarkus/runtime,stacktale-quarkus/deployment -am install
```

The deployment module's `StacktaleExtensionTest` boots the extension in-process (`QuarkusUnitTest`)
and asserts a real report is written — no separate example app needed.

Native-image builds initialize stacktale's writer and JUL handler at runtime, so the generated
binary opens report files only when the application starts, not while GraalVM is building it.
