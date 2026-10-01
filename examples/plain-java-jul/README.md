# Plain Java JUL (java.util.logging) Example

A minimal, standalone plain Java project demonstrating direct `java.util.logging` (JUL) integration using `StacktaleJulHandler`.

## What This Example Demonstrates

- **Direct JUL Integration**: Uses the JDK's built-in `java.util.logging` framework directly via `StacktaleJulHandler` without requiring Spring Boot or Logback.
- **Declarative `logging.properties`**: Demonstrates configuring the handler, package root markers, and output file declaratively using standard JUL `logging.properties`.
- **Thread-Correlated Story**: Since JUL has no native MDC support, `stacktale` automatically correlates preceding `INFO` and `WARNING` logs on the executing thread into the `story` section when a `SEVERE` error occurs.

## How to Run

From this directory (`examples/plain-java-jul`):

```bash
mvn compile exec:exec
```

`exec:exec`, not `exec:java`. `exec:java` runs the app inside Maven's own JVM: JUL is already
configured there, so `java.util.logging.config.file` is ignored, and the JDK loads `handlers=`
classes only through the system class loader, which cannot see the project. It prints the
console lines and writes no report. A forked JVM behaves like your real app.

Or using plain `java`:

```bash
mvn compile dependency:copy-dependencies
java -Djava.util.logging.config.file=src/main/resources/logging.properties \
     -Dstacktale.app.name=order-batch \
     -cp "target/classes:target/dependency/*" com.example.demo.DemoApp
```

On Windows (cmd or PowerShell), write it on one line and use `;` as the `-cp` separator. Quote
the `-D` arguments: PowerShell otherwise splits `-Djava.util…` at the first dot.

```
java "-Djava.util.logging.config.file=src/main/resources/logging.properties" "-Dstacktale.app.name=order-batch" -cp "target/classes;target/dependency/*" com.example.demo.DemoApp
```

## What to Look For

Open the generated report in `target/errors-ai.log`. This is real output, captured from the
command above:

```
━━━ ERROR #8c5fc4eb ━━━ 2026-10-01 20:31:58.848 thread=main ━━━
IllegalArgumentException: Customer email cannot be null for notification dispatch
at DemoApp.processCustomer(DemoApp.java:37) ← YOUR CODE
log: "Failed to process customer 404" logger=c.e.d.DemoApp

story (thread main, last 4 events, 92ms):
  20:31:58.756 INFO  DemoApp  Starting order processing batch
  20:31:58.847 INFO  DemoApp  Fetching customer 404 from database
  20:31:58.848 WARNING DemoApp  Database returned empty record for customer 404
  20:31:58.848 SEVERE DemoApp  Failed to process customer 404   ← this error

stack (distilled, 2 of 2 frames):
  DemoApp.processCustomer(DemoApp.java:37) ← culprit
  DemoApp.main(DemoApp.java:29)

env: app=order-batch | java 21.0.6 | windows
━━━ END #8c5fc4eb ━━━
```

### Key Highlights

- **`SEVERE` Level Trigger**: `SEVERE` JUL log events trigger report generation.
- **Thread-Based Story**: Preceding `INFO` and `WARNING` events on the `main` thread are captured into the `story` block.
- **Package Markers**: `StacktaleJulHandler.appPackages = com.example.demo` highlights user frames with `← YOUR CODE` and `← culprit`.
- **`app=order-batch`**: set with `-Dstacktale.app.name`. Without it the `env:` line says `app=?`.
