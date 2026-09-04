#!/usr/bin/env bash
# Guard the configuration surface across the five adapters.
#
# One knob is one row in the README's configuration table, and five unrelated pieces of
# syntax: a setter on the Logback appender, a @PluginBuilderAttribute field in Log4j2, a
# LogManager property in JUL, an accessor read by the Spring auto-configuration, a method on
# the Quarkus @ConfigMapping. Nothing connects them, so they drift one at a time — and every
# way of drifting is silent. Joran logs `Ignoring unknown property`, @ConfigurationProperties
# drops unknown fields, Quarkus warns and boots. The user sets the knob, sees no error, and
# gets no behaviour (#210: `repro` reached only Log4j2 and JUL, for four releases).
#
# What is checked is *reachability*, not merely that the name appears: each adapter is matched
# on the shape that makes a knob settable — a setter, an attribute declaration, a property
# read, a wiring call. A private field nobody can set does not count, which is exactly the
# state #210 was in.
#
# Not checked, deliberately: `appName` and `appVersion` are settable on the Logback appender
# and nowhere else, and have no README row. Everywhere else the name is derived — Spring Boot
# from spring.application.name (#150), Log4j2 and JUL from the stacktale.app.* system
# properties or META-INF/build-info.properties. Adding rows for them would make this script
# demand four surfaces that should not exist. (Quarkus derives nothing today and reports
# `app=?`; that is #213, a missing feature rather than a knob that drifted.)
#
# Source only, no build needed.
set -euo pipefail

cd "$(dirname "$0")/.."

LOGBACK="stacktale/src/main/java/io/github/gabrielbbaldez/stacktale/logback/StacktaleAppender.java"
LOG4J2="stacktale-log4j2/src/main/java/io/github/gabrielbbaldez/stacktale/log4j2/StacktaleAppender.java"
JUL="stacktale-jul/src/main/java/io/github/gabrielbbaldez/stacktale/jul/StacktaleJulHandler.java"
SPRING="stacktale-spring-boot-starter/src/main/java/io/github/gabrielbbaldez/stacktale/spring/StacktaleAutoConfiguration.java"
QUARKUS="stacktale-quarkus/runtime/src/main/java/io/github/gabrielbbaldez/stacktale/quarkus/runtime/StacktaleRecorder.java"

# Deliberate absences. Every entry is a decision someone made; anything not listed here and not
# found is a bug. Keeping them explicit is the point — today an accidental gap and an
# intentional one look identical from the outside.
allowed() {
  case "$1:$2" in
    jul:correlationMdcKeys)
      echo "JUL has no MDC" ;;
    logback:enabled|log4j2:enabled|jul:enabled)
      echo "master switch belongs to the framework integrations; a raw appender is removed, not disabled" ;;
    logback:requestLogging|log4j2:requestLogging|jul:requestLogging)
      echo "HTTP request lines come from a servlet/reactive filter the plain appenders have no access to" ;;
    *)
      return 1 ;;
  esac
}

# The README is the contract, so the knob list comes from it rather than from a list kept here
# that could drift the same way the adapters did.
knobs="$(sed -n '/^| Property | Default | What it does |/,/^$/p' README.md \
  | grep -oE '^\| `[a-zA-Z.]+`' | tr -d '|` ' | sed 's/^stacktale\.//' || true)"

if [ -z "$knobs" ]; then
  echo "check-config-parity: found no configuration table in README.md" >&2
  exit 1
fi

fail=0
checked=0
skipped=0

for knob in $knobs; do
  # Setter/accessor spelling: repro -> Repro, redactPattern -> RedactPattern.
  cap="$(printf '%s' "${knob:0:1}" | tr '[:lower:]' '[:upper:]')${knob:1}"
  # Spring reads two knobs through @ConditionalOnProperty, which names them in kebab-case.
  kebab="$(printf '%s' "$knob" | sed 's/\([A-Z]\)/-\L\1/g')"

  for adapter in logback log4j2 jul spring quarkus; do
    case "$adapter" in
      # A field is not a knob: match the setter, which is what #210 was missing.
      logback) file="$LOGBACK";  pattern="(set|add)${cap}s?\(" ;;
      log4j2)  file="$LOG4J2";   pattern="@PluginBuilderAttribute[^;]*\b${knob}s?\b" ;;
      # `installUncaughtHandler` is read as class.getName() + ".installUncaughtHandler",
      # not through the `p + "…"` helper — hence the optional leading dot.
      jul)     file="$JUL";      pattern="\"\.?${knob}s?\"" ;;
      # The auto-configuration, not the properties class: a property that binds and is never
      # passed to the appender is the other half of #210.
      spring)  file="$SPRING";   pattern="props\.(is|get)${cap}s?\(|name = \"${kebab}\"" ;;
      quarkus) file="$QUARKUS";  pattern="config\.${knob}s?\(" ;;
    esac

    if [ ! -f "$file" ]; then
      echo "check-config-parity: $adapter source missing at $file" >&2
      fail=1
      continue
    fi

    if grep -qE "$pattern" "$file"; then
      checked=$((checked + 1))
    elif reason="$(allowed "$adapter" "$knob")"; then
      skipped=$((skipped + 1))
      : "$reason"
    else
      echo "check-config-parity: '$knob' is in the README table but $adapter cannot set it" >&2
      echo "                    ($file has no match for /$pattern/)" >&2
      fail=1
    fi
  done
done

if [ "$fail" -eq 0 ]; then
  echo "configuration knobs reachable from every adapter ($checked pairs, $skipped documented exceptions)"
fi

exit "$fail"
