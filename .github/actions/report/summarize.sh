#!/usr/bin/env bash
# Extracts the first N complete st/1 reports from an errors-ai.log and writes a Markdown
# summary. Pure awk on purpose: a composite action that needs node or python is one more
# thing to break on a runner that has neither.
#
#   summarize.sh <log-file> <max-reports> <out-file>
#
# Prints the number of reports found on stdout so the caller can branch on it.
set -euo pipefail

log="$1"
max="$2"
out="$3"

start='━━━ ERROR #'
end='━━━ END #'

# A block is only complete once its END line lands — the writer appends header, session
# marker and block separately, so a read can catch a half-written tail. FORMAT.md requires
# readers to discard those rather than show a partial report.
total=$(grep -c "^${end}" "$log" 2>/dev/null || true)
total=${total:-0}
echo "$total"

if [ "$total" -eq 0 ]; then
  exit 0
fi

shown=$( [ "$total" -lt "$max" ] && echo "$total" || echo "$max" )

{
  if [ "$total" -eq 1 ]; then
    echo "### 🔥 1 error report"
  else
    echo "### 🔥 ${total} error reports"
  fi
  echo
  if [ "$shown" -lt "$total" ]; then
    echo "Showing the first ${shown}. The full \`$(basename "$log")\` is attached to this run as an artifact."
    echo
  fi
  echo '```'
  awk -v s="$start" -v e="$end" -v limit="$shown" '
    index($0, s) == 1 { inblock = 1 }
    inblock { print }
    index($0, e) == 1 {
      inblock = 0
      count++
      if (count >= limit) exit
      print ""
    }
  ' "$log"
  echo '```'
  echo
  echo '<sub>Produced by <a href="https://github.com/stacktale/stacktale">stacktale</a> — root cause first, your culprit frame marked.</sub>'
} > "$out"
