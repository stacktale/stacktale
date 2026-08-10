#!/usr/bin/env bash
# Guard the Claude plugin / marketplace manifests against drifting from the release.
#
# Three files hard-pin a version and are not touched by the version bump:
#   plugins/stacktale/.mcp.json                  -> the stacktale-mcp coordinate jbang runs
#   .claude-plugin/marketplace.json              -> the version the marketplace advertises
#   plugins/stacktale/.claude-plugin/plugin.json -> the version the installed plugin reports
#
# THE RULE: they pin the most recent *released* version, which is the newest version
# heading in CHANGELOG.md.
#
# Not the parent pom's version, because those differ on purpose for most of a cycle. On
# `main` between releases the parent is 1.3.0-SNAPSHOT while the plugin must still install
# 1.2.0 — the only version a user can actually resolve from Maven Central. At release the
# bump sets the parent to 1.2.0 and adds the `## [1.2.0]` heading in the same commit, so
# the two agree again. Keying on the changelog is therefore correct in both states, where
# plain equality with the pom is wrong in one of them.
#
# On mismatch, update the three JSON files to the version named in the failure message.
set -euo pipefail

cd "$(dirname "$0")/.."

fail=0

# Newest `## [X.Y.Z]` heading. `[Unreleased]`, if it is ever added, is skipped by the
# version pattern rather than by position.
latest_release() {
  grep -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' CHANGELOG.md \
    | head -n1 | tr -d '#[] '
}

# The parent's own <version>, read without invoking Maven so this runs on a bare checkout.
pom_version() {
  sed -n '/<artifactId>stacktale-parent<\/artifactId>/,/<\/version>/p' pom.xml \
    | grep -oE '<version>[^<]+</version>' | head -n1 | sed 's/<[^>]*>//g'
}

# First version-shaped string in a file, which is the pinned one in all three.
pinned() {
  grep -oE '[0-9]+\.[0-9]+\.[0-9]+' "$1" | head -n1
}

expected=$(latest_release)
if [ -z "$expected" ]; then
  echo "check-plugin-versions: no '## [X.Y.Z]' heading found in CHANGELOG.md" >&2
  exit 1
fi

for file in \
  plugins/stacktale/.mcp.json \
  .claude-plugin/marketplace.json \
  plugins/stacktale/.claude-plugin/plugin.json
do
  actual=$(pinned "$file")
  if [ "$actual" != "$expected" ]; then
    echo "$file pins $actual, expected $expected (the latest release, per CHANGELOG.md)" >&2
    fail=1
  fi
done

# A release commit sets the parent to the version it is releasing. If the parent is not a
# SNAPSHOT and disagrees with the changelog, one of the two was not updated -- and the
# check above would have compared against the wrong number without noticing.
pom=$(pom_version)
case "$pom" in
  *-SNAPSHOT) ;;
  "$expected") ;;
  *)
    echo "pom.xml is $pom but CHANGELOG.md's latest release is $expected" >&2
    echo "  a release bumps both; fix whichever is behind before trusting the pins above" >&2
    fail=1
    ;;
esac

if [ "$fail" -eq 0 ]; then
  echo "plugin manifests pin $expected"
fi

exit "$fail"
