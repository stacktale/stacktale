# Plain Java JUL (java.util.logging) Example

A minimal, standalone plain Java project demonstrating direct `java.util.logging` (JUL) integration using `StacktaleJulHandler`.

## What This Example Demonstrates

- **Direct JUL Integration**: Uses the JDK's built-in `java.util.logging` framework directly via `StacktaleJulHandler` without requiring Spring Boot or Logback.
- **Declarative `logging.properties`**: Demonstrates configuring the handler, package root markers, and output file declaratively using standard JUL `logging.properties`.
- **Thread-Correlated Story**: Since JUL has no native MDC support, `stacktale` automatically correlates preceding `INFO` and `WARNING` logs on the executing thread into the `story` section when a `SEVERE` error occurs.

## How to Run

From this directory (`examples/plain-java-jul`):

```bash
mvn compile exec:java
```

Or using standard `java`:

```bash
mvn compile dependency:copy-dependencies
java -Djava.util.logging.config.file=src/main/resources/logging.properties -cp "target/classes:target/dependency/*" com.example.demo.DemoApp
```

## What to Look For

Open the generated report in `target/errors-ai.log`:

```
━━━ ERROR #... ━━━ ... thread=main ━━━
IllegalArgumentException: Customer email cannot be null for notification dispatch
at com.example.demo.DemoApp.processCustomer(DemoApp.java:34) ← YOUR CODE
log: "Failed to process customer 404" logger=com.example.demo.DemoApp

story (last 3 events):
  08:54:00.100 INFO    com.example.demo.DemoApp  Starting order processing batch
  08:54:00.101 INFO    com.example.demo.DemoApp  Fetching customer 404 from database
  08:54:00.105 WARNING com.example.demo.DemoApp  Database returned empty record for customer 404
  08:54:00.110 SEVERE  com.example.demo.DemoApp  Failed to process customer 404   ← this error

stack (distilled):
  com.example.demo.DemoApp.processCustomer(DemoApp.java:34) ← culprit
  com.example.demo.DemoApp.main(DemoApp.java:27)
```

### Key Highlights

- **`SEVERE` Level Trigger**: `SEVERE` JUL log events trigger report generation.
- **Thread-Based Story**: Preceding `INFO` and `WARNING` events on the `main` thread are captured into the `story` block.
- **Package Markers**: `StacktaleJulHandler.appPackages = com.example.demo` highlights user frames with `← YOUR CODE` and `← culprit`.
