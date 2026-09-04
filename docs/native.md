# stacktale on GraalVM native-image

stacktale's report pipeline is almost entirely reflection-free, so it works under
GraalVM native-image — Spring Boot 3 AOT and Quarkus alike — with **no configuration**. The
one section that needs your help is `fields:`, because it reflects over *your* exception
types, which only you can enumerate for the image.

The Quarkus section below is measured rather than assumed: a native binary, a real error, and
the report it wrote.

## What works with zero metadata

Everything that makes a report except `fields:` and `captured:`:

- the headline, culprit frame, `wrapped by:`, distilled stack
- the `story` (events leading up to the error), dedup, `seen:` recurrence
- redaction, storm control, session markers
- writing `errors-ai.log` and the MCP server reading it

None of these touch reflection or dynamic resources.

## `env:` — handled for you

The `env:` line reads two classpath resources (`git.properties` and
`META-INF/build-info.properties`). Native-image drops unregistered resources, so
stacktale ships the metadata to keep them:

- **stacktale-core** bundles
  `META-INF/native-image/io.github.gabrielbbaldez/stacktale-core/resource-config.json`.
- **the Spring Boot starter** additionally registers them through a
  `RuntimeHintsRegistrar` (`StacktaleRuntimeHints`).

Without these you don't get a crash — just `app=?` with no version/sha. With them,
`env:` is complete in native too.

## `fields:` — register your exception types (the escape hatch)

`fields:` reads the getters and public fields of *your* domain exceptions
(`order.getOrderId()`, `retryable`). Under native-image, reflection over a class only
works if that class is registered, and stacktale cannot know your exception types ahead
of time. Unregistered, `fields:` simply comes back empty (no crash) — a graceful
degradation, not a failure.

To keep `fields:` working, register your exception types. Two ways:

**Spring Boot** — point a hint at your exceptions (a package's worth in one line):

```java
@Configuration
@RegisterReflectionForBinding({ OrderException.class, PaymentException.class })
class NativeHints {}
```

or a registrar if you prefer:

```java
class MyHints implements RuntimeHintsRegistrar {
    public void registerHints(RuntimeHints hints, ClassLoader cl) {
        hints.reflection().registerType(OrderException.class,
                MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS);
    }
}
```

**Plain native-image** — add a `reflect-config.json` (under
`META-INF/native-image/<your-group>/<your-artifact>/`):

```json
[
  {
    "name": "com.acme.OrderException",
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }
]
```

Only exception types you care about seeing state for need this — stacktale reads at most
8 value-typed getters/fields per report.

## Quarkus — measured, and it needs nothing

`stacktale-quarkus` is the module where this matters most: native image is a first-class
Quarkus story rather than an afterthought, and the extension's whole shape — the
runtime/deployment split, the `@Recorder`, deciding configuration at build time — exists so
that Quarkus can close the world at build time.

It works with **no configuration**. Same application, same error, JVM mode and native side by
side:

| | JVM | native |
|---|---|---|
| a report is written at all | yes | yes |
| headline, culprit frame, `← YOUR CODE` | yes | yes |
| `story`, distilled `stack`, dedup | yes | yes |
| `env:` including the git sha | yes | yes |
| `first seen:` and its sidecar | yes | yes |
| `fields:` | yes | **empty** until you register the type |

Two things carry `env:` through, and neither needs anything from you:

- `git.properties` survives because **Quarkus honours the `META-INF/native-image/…`
  resource metadata that `stacktale-core` already bundles**. No `NativeImageResourceBuildItem`
  is needed in the extension; the resource registration a plain native-image build reads is
  read here too.
- the application **name and version** do not come from a resource at all — the extension
  reads `quarkus.application.name` and `.version` at build time and records them, so they are
  compiled into the image.

The extension also marks `ReportPipeline` and `StacktaleJulHandler` runtime-initialized, which
is what keeps a file handle and a background flusher out of the image heap. (Whether a build
would fail without that was not isolated — a passing build with it removed would not prove much
either way, since nothing in a minimal app forces those classes to initialize at build time.)

### `fields:` in Quarkus

Same rule as everywhere else — reflection over *your* exception types — with Quarkus's own
annotation:

```java
@RegisterForReflection
public class OrderException extends IllegalStateException {
    public long getOrderId() { ... }
}
```

Verified both ways on the same binary: without the annotation the section is absent, with it
the report carries `fields: orderId=999 retryable=false`. As everywhere, an unregistered type
degrades to an empty section rather than a crash.

### What this was tested against

**Quarkus 3.15.1** — the floor of the compatibility matrix — with Oracle GraalVM 21.0.5
(native-image 23.1), on Windows.

Not the top of the range, and that is worth knowing rather than glossing: Quarkus 3.39 refuses
to build native with anything older than GraalVM/Mandrel **25.0.0**, so checking the current
version needs a newer toolchain than the floor does:

```
Out of date version of GraalVM or Mandrel detected: 23.1.
Quarkus currently supports 25.0.0.
```

Nothing in the extension's native surface is version-specific — a bundled resource
registration and a build-time recorded value — so the result should hold across the range. It
has not been observed there.

## `captured:` — not available in native

The `captured:` section comes from the optional `stacktale-agent`, a `-javaagent`.
Java agents instrument bytecode at class-load time and do not apply to a native image,
so `captured:` is simply absent in native builds. `AgentCaptures` no-ops when the agent
class isn't present — again, graceful, no crash.

## Verifying

With a GraalVM JDK, build your app native the usual way — stacktale needs no special flags
beyond registering your exception types (above).

```bash
mvn -Pnative native:compile        # Spring Boot
mvn package -Dnative               # Quarkus
./target/your-app                  # trigger an error, then check ./errors-ai.log
```

Check the report itself, not the build. Native-image failures of this kind are **runtime**
failures — a missing resource or a pruned reflective call — so the binary links happily and
then the section is quietly absent. What to look at:

- `env:` keeps your version and git sha,
- `fields:` is populated for every exception type you registered,
- and there is a report at all.

There is no native job in CI: native-image needs a GraalVM toolchain and minutes per build, and
the toolchain does not follow the compatibility matrix — Quarkus 3.39 requires GraalVM 25 while
3.15 accepts 23.1, so one runner cannot cover the range. Run this yourself when validating a
native deployment.
