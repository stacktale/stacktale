#!/usr/bin/env bash
# Guard the Claude plugin / marketplace manifests against drifting from the release.
#
# Six files hard-pin a version, and the bump either does not touch them or was silently
# failing to:
#   plugins/stacktale/.mcp.json                  -> the stacktale-mcp coordinate jbang runs
#   .claude-plugin/marketplace.json              -> the version the marketplace advertises
#   plugins/stacktale/.claude-plugin/plugin.json -> the version the installed plugin reports
#   docs/mcp-setup.md                            -> the coordinate a human copy-pastes
#   README.md                                    -> the install snippet on the front page
#   stacktale-quarkus/README.md                  -> the same, for the extension
#
# The fourth was added after it drifted the furthest of any of them: mcp-setup.md sat on
# 1.1.0 through three releases while the page above the command promised tools that version
# did not have. It is the one a person actually runs, and nothing checked it.
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

# Prose, so every occurrence is checked rather than the first: a page can update one command
# and leave the three below it behind, which reads worse than a single stale number.
setup_pins=$(grep -oE 'io\.github\.gabrielbbaldez:stacktale-mcp:[0-9]+\.[0-9]+\.[0-9]+' \
  docs/mcp-setup.md | sed 's/.*://' | sort -u || true)
if [ -z "$setup_pins" ]; then
  echo "docs/mcp-setup.md names no stacktale-mcp coordinate; the install instructions lost it" >&2
  fail=1
fi
for actual in $setup_pins; do
  if [ "$actual" != "$expected" ]; then
    echo "docs/mcp-setup.md pins $actual, expected $expected (the latest release, per CHANGELOG.md)" >&2
    fail=1
  fi
done

# The install snippets on the front page, which are the first thing anybody copies.
#
# These were the worst of the lot and nothing was watching them: README.md told Maven users
# to depend on 1.2.0 through 1.3.0, 1.3.1 and 1.4.0, with the Gradle line directly beneath
# it saying 1.4.0. stacktale-quarkus/README.md was pointing at 1.3.0-SNAPSHOT, which Central
# cannot resolve at all. The bump's own regex had never matched them -- see the comment in
# prepare-release.yml -- and a substitution that matches nothing leaves no trace.
#
# The <version> is read out of the element rather than matched as a version-shaped string,
# so `1.3.0-SNAPSHOT` is reported as the wrong pin it is instead of slipping past a
# `[0-9.]+` pattern.
maven_pins() { # <file> -> the <version> of each stacktale dependency block
  awk '
    /<artifactId>stacktale[a-z0-9-]*<\/artifactId>/ { want = 1 }
    want && match($0, /<version>[^<]+<\/version>/) {
      print substr($0, RSTART + 9, RLENGTH - 19); want = 0
    }
    /<\/dependency>/ { want = 0 }
  ' "$1"
}

for file in README.md stacktale-quarkus/README.md; do
  pins=$(maven_pins "$file" | sort -u)
  if [ -z "$pins" ]; then
    echo "$file has no stacktale <dependency> block; the install snippet lost its version" >&2
    fail=1
  fi
  for actual in $pins; do
    if [ "$actual" != "$expected" ]; then
      echo "$file installs $actual, expected $expected (the latest release, per CHANGELOG.md)" >&2
      fail=1
    fi
  done
done

# The Gradle one-liners beside them. These the bump did reach, which is exactly why they
# disagreed with the Maven block above and nobody noticed: each half looked internally
# consistent.
gradle_pins=$(grep -ohE 'io\.github\.gabrielbbaldez:stacktale[a-z0-9-]*:[0-9]+\.[0-9]+\.[0-9]+' \
  README.md | sed 's/.*://' | sort -u || true)
for actual in $gradle_pins; do
  if [ "$actual" != "$expected" ]; then
    echo "README.md's Gradle coordinates pin $actual, expected $expected (per CHANGELOG.md)" >&2
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
  echo "install snippets, plugin manifests and setup docs pin $expected"
fi

exit "$fail"
