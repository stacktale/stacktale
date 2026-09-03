#!/usr/bin/env bash
# Guard the published JPMS module names.
#
# Each module declares `stacktale.module.name`, which the parent pom writes into the jar's
# manifest as Automatic-Module-Name. Consumers put that string in a `requires` directive, so
# it is a published API: changing it breaks them, and shipping a typo means shipping a name
# we cannot take back.
#
# The README documents the names in a table and JpmsModulePathIT resolves two of them on the
# module path; neither notices a typo in the rest. This checks all three agree:
#
#   pom property  ==  the jar's Automatic-Module-Name  ==  a row in README.md
#
# Reads built jars, so it needs a package first. CI runs it after `mvn verify`.
set -euo pipefail

cd "$(dirname "$0")/.."

version="$(mvn -q help:evaluate -DforceStdout -Dexpression=project.version 2>/dev/null)"
fail=0
checked=0

while IFS= read -r pom; do
  dir="$(dirname "$pom")"

  # `|| true` throughout: the root pom references the property without declaring one, and a
  # grep that matches nothing exits 1, which `set -e` would take as this script failing.
  declared="$(grep -oE '<stacktale\.module\.name>[^<]+' "$pom" | sed 's/.*>//' || true)"
  [ -z "$declared" ] && continue

  # Drop <parent> first: its artifactId precedes the module's own, so a plain first-match
  # grep names the parent and every jar path built from it points at nothing.
  artifact="$(sed '/<parent>/,/<\/parent>/d' "$pom" \
    | grep -m1 -oE '<artifactId>[^<]+' | sed 's/.*>//' || true)"

  jar="$dir/target/$artifact-$version.jar"
  if [ ! -f "$jar" ]; then
    echo "check-module-names: $artifact has no jar at $jar — package first" >&2
    fail=1
    continue
  fi

  # The manifest wraps at 72 bytes and continues on the next line with a leading space, and
  # these names reach it: the quarkus jars carry '...quarkus.runtim' then ' e'. Unfold before
  # reading, or every long name looks truncated and the check cries wolf.
  manifest="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' \
    | awk '{ if (substr($0,1,1) == " ") printf "%s", substr($0,2); else printf "\n%s", $0 } END { print "" }' \
    | grep -m1 '^Automatic-Module-Name:' | sed 's/^Automatic-Module-Name: *//' || true)"

  if [ "$manifest" != "$declared" ]; then
    echo "check-module-names: $artifact declares '$declared' but its jar says '$manifest'" >&2
    fail=1
  fi

  if ! grep -qF "\`$declared\`" README.md; then
    echo "check-module-names: $artifact publishes '$declared', absent from the README table" >&2
    fail=1
  fi

  checked=$((checked + 1))
done < <(grep -rl "stacktale.module.name" --include=pom.xml . | grep -v "/target/")

if [ "$fail" -eq 0 ]; then
  echo "module names agree across pom, jar manifest and README ($checked modules)"
fi

exit "$fail"
