#!/usr/bin/env bash
# Guard the hot path against a regression, by comparison rather than against a number.
#
# THE PROBLEM WITH A THRESHOLD. #98 asked for a JMH run in CI failing past a fixed
# threshold "with headroom to avoid flakiness on shared runners". Measured on an idle
# developer machine — quieter than any shared runner — the same code timed 5092 ns/op in one
# session and 5581 in another minutes later. That is ~8% of drift with nothing changed, and
# the regressions worth catching are not much larger. Headroom wide enough to survive that
# lets a real regression through; headroom narrow enough to catch one fires on a noisy
# neighbour. Both end with somebody muting the job.
#
# WHAT THIS DOES INSTEAD. Build the pull request and its merge base, then run the same probe
# against them **alternately**, in one sitting. Drift hits both arms inside the same few
# seconds and cancels in the ratio. The comparison is base-vs-head medians; the absolute
# numbers are printed for a human but nothing is asserted about them.
#
# The third-party dependencies come from the head build for both arms on purpose: the
# question is what *this* change cost, not what a Logback upgrade cost.
#
# WHAT IT IS CALIBRATED TO CATCH. #98 wants to stop "a refactor silently 10x-ing the hot
# path", and that is the size of thing this reliably reports. It is not a 5% detector, and
# pretending otherwise would make it a liar: run against a null — the same commit on both
# arms, true ratio 1.000 — the measured ratios came back
#
#   400k/20k iterations, 1 alternation    info 1.188   error 0.931
#   400k/20k iterations, 5 alternations   info 0.938   error 1.117
#   2M/100k iterations,  3 alternations   info 0.865   error 0.935
#
# Longer rounds tightened the error path; the happy path stayed at roughly +-10% however it
# was measured, because at ~200ns/op the variation is between JVM processes — each arm gets
# its own JIT compilation plan — and alternating cancels drift, not that. So the limit sits
# well clear of the noise floor, and a ratio creeping toward it is visible in the printed
# numbers long before it fails.
#
#   BASE_REF   what to compare against            (default: origin/main)
#   ROUNDS     alternations per arm               (default: 3)
#   MAX_RATIO  head/base median that fails        (default: 1.5)
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_REF="${BASE_REF:-origin/main}"
ROUNDS="${ROUNDS:-3}"
MAX_RATIO="${MAX_RATIO:-1.5}"

work="$(mktemp -d)"
base_tree="$work/base"
cleanup() {
  git worktree remove --force "$base_tree" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# Windows/Git Bash: java wants ';' between classpath entries, and MSYS rewrites anything that
# looks like a path list unless told not to. Scoped to the java/javac calls rather than
# exported — setting it for the whole script breaks Maven's own launcher, which depends on the
# conversion it disables.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';'; NO_CONV='MSYS2_ARG_CONV_EXCL=*'; WINDOWS=1 ;;
  *) SEP=':'; NO_CONV=''; WINDOWS=0 ;;
esac

# With conversion disabled, a POSIX path reaches the JVM unchanged and is not a path there:
# the classpath entry silently does not exist, Logback falls back to its default console
# config, and the probe times something else entirely. Convert explicitly instead.
native_path() {
  if [ "$WINDOWS" -eq 1 ]; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

base_sha="$(git rev-parse "$BASE_REF")"
head_sha="$(git rev-parse HEAD)"
if [ "$base_sha" = "$head_sha" ]; then
  echo "check-perf: HEAD is $BASE_REF; nothing to compare"
  exit 0
fi

echo "check-perf: comparing $(git rev-parse --short HEAD) against $BASE_REF ($(git rev-parse --short "$base_sha"))"

build() { # <tree> <label>
  ( cd "$1" && mvn -q -B -pl stacktale -am -DskipTests package ) \
    || { echo "check-perf: could not build the $2 tree" >&2; exit 1; }
}

git worktree add --detach "$base_tree" "$base_sha" >/dev/null 2>&1
build "." "head"
build "$base_tree" "base"

# One classpath of third-party jars, from the head build, shared by both arms.
#
# Resolved inside the same reactor run as the build (`-am`) rather than as a standalone
# invocation: on a clean machine stacktale-core:<version>-SNAPSHOT is not in the local
# repository, and a standalone goal has no reactor to find it in. That passes on a developer
# machine, where an earlier `install` left it there, and fails on a fresh runner — which is
# where it did fail.
#
# Our own artifacts are excluded, so `deps` is third-party only and cannot shadow the arm
# being measured.
mvn -q -B -pl stacktale -am -DskipTests package     dependency:build-classpath     -Dmdep.outputFile="$work/cp.txt"     -DincludeScope=runtime     -DexcludeGroupIds=io.github.gabrielbbaldez
deps="$(cat "$work/cp.txt")"

# The probe is compiled once, from the head checkout, and run against both. It uses only
# SLF4J and the appender's class name — a probe calling stacktale's own API would stop
# compiling against an older base and report that as a regression.
mkdir -p "$work/probe"
# Both arguments need the native form for the same reason: with conversion off, `-d` would
# create a literal `C:\c\Users\…` tree and the class would not be where the classpath looks.
env ${NO_CONV:+"$NO_CONV"} javac -nowarn \
    -cp "$deps" \
    -d "$(native_path "$work/probe")" \
    "$(native_path "$PWD/scripts/perf/PerfProbe.java")"

arm_cp() { # <tree>
  local entries=(
    "$work/probe"                        # the compiled probe
    "$PWD/scripts/perf"                  # logback-perf.xml
    "$1/stacktale/target/classes"        # the arm under measurement
    "$1/stacktale-core/target/classes"
  )
  local out=""
  local entry
  for entry in "${entries[@]}"; do
    out="$out$(native_path "$entry")$SEP"
  done
  printf '%s%s' "$out" "$deps"
}

report_file="$(native_path "$work/perf-errors-ai.log")"

run() { # <tree> -> "info=<ns>\nerror=<ns>"
  env ${NO_CONV:+"$NO_CONV"} java \
      -Dlogback.configurationFile=logback-perf.xml \
      -Dstacktale.perf.file="$report_file" \
      -cp "$(arm_cp "$1")" PerfProbe
}

base_info=(); base_error=(); head_info=(); head_error=()
for round in $(seq 1 "$ROUNDS"); do
  # base first on odd rounds, head first on even: whichever runs first pays for a cold page
  # cache, and alternating the order keeps that from landing on one arm every time.
  if [ $((round % 2)) -eq 1 ]; then order="$base_tree ."; else order=". $base_tree"; fi
  for tree in $order; do
    out="$(run "$tree")"
    info="$(printf '%s\n' "$out" | sed -n 's/^info=//p')"
    error="$(printf '%s\n' "$out" | sed -n 's/^error=//p')"
    if [ -z "$info" ] || [ -z "$error" ]; then
      echo "check-perf: the probe produced no measurement for $tree" >&2
      printf '%s\n' "$out" >&2
      exit 1
    fi
    if [ "$tree" = "." ]; then head_info+=("$info"); head_error+=("$error")
    else base_info+=("$info"); base_error+=("$error"); fi
  done
  echo "  round $round done"
done

median() { printf '%s\n' "$@" | sort -n | awk '{ v[NR]=$1 } END { print v[int((NR+1)/2)] }'; }

fail=0
for metric in info error; do
  if [ "$metric" = "info" ]; then
    b="$(median "${base_info[@]}")"; h="$(median "${head_info[@]}")"
  else
    b="$(median "${base_error[@]}")"; h="$(median "${head_error[@]}")"
  fi
  ratio="$(awk -v h="$h" -v b="$b" 'BEGIN { printf "%.3f", (b > 0 ? h / b : 0) }')"
  printf '  %-6s base %6s ns   head %6s ns   ratio %s\n' "$metric" "$b" "$h" "$ratio"
  over="$(awk -v r="$ratio" -v m="$MAX_RATIO" 'BEGIN { print (r > m) ? 1 : 0 }')"
  if [ "$over" -eq 1 ]; then
    echo "check-perf: $metric is ${ratio}x the base, over the $MAX_RATIO limit" >&2
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "check-perf: within $MAX_RATIO of $BASE_REF over $ROUNDS alternations"
fi

exit "$fail"
