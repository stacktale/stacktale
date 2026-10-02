#!/usr/bin/env bash
# Run every example under examples/ and check its report says what its README promises.
#
# The examples are where a newcomer checks whether the README is true, and for a while they
# were not: the JUL example's documented command wrote no report at all (exec:java runs inside
# Maven's JVM, where JUL never installs the handler), the MVC example promised `wrapped by:`
# and `fields:` while logging the cause before wrapping it, and the WebFlux example's story
# stopped at one line because nothing put context-propagation on its classpath. Every one of
# those was silent — the app ran, the console looked normal, the report was just missing or
# thin. So this runs each example for real and greps the report for the sections its README
# shows.
#
# Needs Maven, a JDK 17+ and curl, and network access for the examples' dependencies. The
# examples depend on the released stacktale from Maven Central; to check them against this
# checkout instead, `mvn -q install -DskipTests` first and set STACKTALE_VERSION to the
# project version.
#
# Each example is started with exactly the command its README documents (`mvn compile
# exec:exec`, `mvn spring-boot:run`), so the README and this check cannot drift apart.
# spring-boot:run forks the app into a child JVM that outlives a killed Maven, so a Spring
# app is stopped through whoever listens on its port.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)

MVN=(mvn -q -B)
[ -n "${STACKTALE_VERSION:-}" ] && MVN+=("-Dstacktale.version=$STACKTALE_VERSION")

fail=0

# expect <report> <example> <fixed string>...: each string must appear in the report
expect() {
  local report=$1 example=$2; shift 2
  local missing=0
  # Anchored: the file's header comment quotes both delimiters too.
  if ! grep -q '^━━━ ERROR #[0-9a-f]' "$report" || ! grep -q '^━━━ END #[0-9a-f]' "$report"; then
    echo "  ✗ $example: no complete ━━━ ERROR … ━━━ END block in $report"
    missing=1
  fi
  for want in "$@"; do
    if ! grep -qF -- "$want" "$report"; then
      echo "  ✗ $example: report lacks: $want"
      missing=1
    fi
  done
  if [ "$missing" -eq 0 ]; then
    echo "  ✓ $example"
  else
    echo "    --- $report ---"
    sed -n '/^━━━ ERROR #[0-9a-f]/,/^━━━ END #[0-9a-f]/p' "$report" | sed 's/^/    /'
    fail=1
  fi
}

# stop_port <port>: kill whatever listens on the port (the app JVM spring-boot:run forked)
stop_port() {
  local port=$1 pids
  if command -v taskkill >/dev/null 2>&1; then # Git Bash on Windows
    # A listening socket is the one whose foreign address has port 0. The state column is
    # not used: Windows localizes it ("LISTENING", "ABHÖREN", "ESCUCHANDO"...).
    pids=$(netstat -ano | awk -v p=":$port" '$1 ~ /^TCP/ && $2 ~ p"$" && $3 ~ /:0$/ && $NF != 0 {print $NF}' | sort -u)
    for p in $pids; do taskkill //F //T //PID "$p" >/dev/null 2>&1 || true; done
  elif command -v lsof >/dev/null 2>&1; then
    pids=$(lsof -t -iTCP:"$port" -sTCP:LISTEN || true)
    [ -n "$pids" ] && kill $pids 2>/dev/null || true
  else
    fuser -k -n tcp "$port" >/dev/null 2>&1 || true
  fi
}

# run_boot <dir> <port> <curl args...>: mvn spring-boot:run, trigger the error once, stop
run_boot() {
  local dir=$1 port=$2; shift 2
  local log="$ROOT/$dir/target/check-examples.out"
  mkdir -p "$dir/target"
  rm -f "$dir/errors-ai.log"
  # Compile first, so a cold dependency cache is downloaded here and not inside the startup
  # wait below, where it would eat into the timeout meant for the app starting.
  if ! (cd "$dir" && "${MVN[@]}" compile >"$log" 2>&1); then
    echo "  ✗ $dir: compile failed — see $log"
    fail=1
    return 1
  fi
  (cd "$dir" && exec "${MVN[@]}" spring-boot:run >>"$log" 2>&1) &
  local pid=$!
  local code=000
  for _ in $(seq 1 180); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$@" || true)
    [ "$code" != 000 ] && break
    kill -0 "$pid" 2>/dev/null || break # Maven exited: build or startup failed
    sleep 1
  done
  sleep 1 # the report is written on the request thread; give the file a beat to flush
  stop_port "$port"
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  if [ "$code" = 000 ]; then
    echo "  ✗ $dir: never answered on port $port — see $log"
    fail=1
    return 1
  fi
}

echo "plain-java-jul (mvn compile exec:exec)"
jul=examples/plain-java-jul
rm -f "$jul/target/errors-ai.log"
(cd "$jul" && "${MVN[@]}" compile exec:exec >/dev/null 2>&1) || { echo "  ✗ $jul: run failed"; fail=1; }
if [ -f "$jul/target/errors-ai.log" ]; then
  expect "$jul/target/errors-ai.log" "$jul" \
    'IllegalArgumentException: Customer email cannot be null' \
    '← YOUR CODE' \
    'story (thread main, last 4 events' \
    'Database returned empty record for customer 404' \
    '← culprit' \
    'env: app=order-batch'
else
  echo "  ✗ $jul: no report at $jul/target/errors-ai.log"
  fail=1
fi

echo "spring-boot-mvc (POST /orders/123/confirm)"
mvc=examples/spring-boot-mvc
if run_boot "$mvc" 8081 -X POST http://localhost:8081/orders/123/confirm; then
  expect "$mvc/errors-ai.log" "$mvc" \
    'NullPointerException: Cannot invoke' \
    'wrapped by: OrderConfirmationException' \
    'mdc: traceId=' \
    'fields: failedStep=send-confirmation-email orderId=123 retryable=false' \
    'story (traceId=' \
    'POST /orders/123/confirm' \
    'Cache miss for customer on order 123' \
    'env: app=shop-demo'
fi

echo "spring-boot-webflux (GET /quotes/314)"
flux=examples/spring-boot-webflux
if run_boot "$flux" 8082 http://localhost:8082/quotes/314; then
  expect "$flux/errors-ai.log" "$flux" \
    'IllegalStateException: pricing feed disconnected' \
    'mdc: traceId=' \
    'story (traceId=' \
    'GET /quotes/314' \
    'pricing lookup for instrument 314' \
    'env: app=quote-demo'
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "An example's report no longer matches what its README shows."
  echo "Fix the example (or the regression in stacktale) — not the expectations above."
  exit 1
fi
echo "Every example writes the report its README shows."
