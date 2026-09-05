#!/usr/bin/env bash
# Guard the documented MCP tool surface.
#
# StacktaleMcpServer registers the tools; three documents describe them, and none of the
# three knows when a tool is added. #218 added `repro_for` and left the README saying "Six
# tools"; it was caught by hand two PRs later. That is the same failure mode #210 and #203
# already have a guard for.
#
# Two halves, because they fail differently:
#
#   every registered name appears in all three documents  -- tells you which file you forgot
#   the "**N tools**" count equals the number registered  -- what a reader trusts at a glance
#
# The count is spelled in words. Rather than teach a shell script English, the words it has
# met are mapped below; an unmapped one is reported rather than skipped, so growing past the
# list fails loudly instead of silently passing.
set -euo pipefail

cd "$(dirname "$0")/.."

server="stacktale-mcp/src/main/java/io/github/gabrielbbaldez/stacktale/mcp/StacktaleMcpServer.java"
docs=("README.md" "docs/mcp-setup.md" "plugins/stacktale/README.md")

fail=0

# `|| true`: a grep that matches nothing exits 1, which `set -e` would take as this script
# failing rather than as the finding it is.
names="$(grep -oE 'tools\.add\(tool\("[a-z_]+"' "$server" | sed 's/.*"\(.*\)"/\1/' || true)"
if [ -z "$names" ]; then
  echo "check-mcp-tools: no tool registrations found in $server — has the call shape changed?" >&2
  exit 1
fi
count="$(printf '%s\n' "$names" | wc -l | tr -d ' ')"

for doc in "${docs[@]}"; do
  if [ ! -f "$doc" ]; then
    echo "check-mcp-tools: $doc is missing" >&2
    fail=1
    continue
  fi
  while IFS= read -r name; do
    if ! grep -qF "$name" "$doc"; then
      echo "check-mcp-tools: the server registers '$name', absent from $doc" >&2
      fail=1
    fi
  done <<< "$names"
done

# The words this sentence has needed so far. Add to the map when the surface grows.
word_for() {
  case "$1" in
    1) echo "One" ;;   2) echo "Two" ;;    3) echo "Three" ;; 4) echo "Four" ;;
    5) echo "Five" ;;  6) echo "Six" ;;    7) echo "Seven" ;; 8) echo "Eight" ;;
    9) echo "Nine" ;;  10) echo "Ten" ;;   11) echo "Eleven" ;; 12) echo "Twelve" ;;
    *) echo "" ;;
  esac
}
want="$(word_for "$count")"
if [ -z "$want" ]; then
  echo "check-mcp-tools: $count tools registered, and this script has no word for that number" >&2
  echo "check-mcp-tools: add it to word_for, or write the sentence with a digit" >&2
  exit 1
fi

for doc in "${docs[@]}"; do
  [ -f "$doc" ] || continue
  claimed="$(grep -oE '\*\*[A-Z][a-z]+ tools\*\*' "$doc" | head -1 | sed 's/\*\*\([A-Za-z]*\) tools\*\*/\1/' || true)"
  [ -z "$claimed" ] && continue      # this document does not open with a count
  if [ "$claimed" != "$want" ]; then
    echo "check-mcp-tools: $doc says '**$claimed tools**'; the server registers $count ($want)" >&2
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "MCP tool surface agrees with all three documents ($count tools)"
fi

exit "$fail"
