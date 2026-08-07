#!/usr/bin/env bash
# Extracts the first N complete st/1 or st-json/1 reports from an errors-ai.log and writes
# a Markdown summary. Pure awk for the text format on purpose: a composite action that needs
# node or python is one more thing to break on a runner that has neither. st-json/1 uses jq
# when present (GitHub-hosted runners have it); otherwise we say so honestly.
#
#   summarize.sh <log-file> <max-reports> <out-file>
#
# Prints the number of reports found on stdout so the caller can branch on it.
set -euo pipefail

log="$1"
max="$2"
out="$3"

is_st_json_log() {
  local first
  first=$(grep -m1 -v '^[[:space:]]*$' "$log" 2>/dev/null || true)
  [ -n "$first" ] || return 1
  [[ "$first" == \{* ]] || return 1
  if command -v jq >/dev/null 2>&1; then
    echo "$first" | jq -e '.format == "st-json/1"' >/dev/null 2>&1
  else
    [[ "$first" == *"st-json/1"* ]]
  fi
}

count_st_json_reports() {
  local count=0 line type
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -z "$line" ] || [[ "$line" != \{* ]] && continue
    type=$(echo "$line" | jq -r '.type // empty' 2>/dev/null) || continue
    if [ "$type" = "report" ]; then
      count=$((count + 1))
    fi
  done < "$log"
  echo "$count"
}

json_headline() {
  echo "$1" | jq -r '
    if .error.noException == true then
      "ERROR (no exception): " + (.error.message // "")
    else
      (.error.type // "") as $t |
      (.error.message // "") as $m |
      if $m == "" then $t else $t + ": " + $m end
    end
  ' 2>/dev/null
}

summarize_st_json() {
  local total="$1"
  local shown
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
    local count=0 line type headline
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line#"${line%%[![:space:]]*}"}"
      line="${line%"${line##*[![:space:]]}"}"
      [ -z "$line" ] || [[ "$line" != \{* ]] && continue
      type=$(echo "$line" | jq -r '.type // empty' 2>/dev/null) || continue
      [ "$type" = "report" ] || continue
      count=$((count + 1))
      headline=$(json_headline "$line")
      id=$(echo "$line" | jq -r '.id // empty' 2>/dev/null)
      ts=$(echo "$line" | jq -r '.ts // empty' 2>/dev/null)
      echo "#### ${headline}"
      if [ -n "$id" ]; then
        echo
        echo "_id=${id}${ts:+, ${ts}}_"
      fi
      echo
      echo '```json'
      echo "$line" | jq .
      echo '```'
      echo
      if [ "$count" -ge "$shown" ]; then
        break
      fi
    done < "$log"
    echo '<sub>Produced by <a href="https://github.com/stacktale/stacktale">stacktale</a> — root cause first, your culprit frame marked.</sub>'
  } > "$out"
}

if is_st_json_log; then
  if ! command -v jq >/dev/null 2>&1; then
    {
      echo "### stacktale report"
      echo
      echo "This log is **st-json/1** (NDJSON). This action cannot summarize it without \`jq\`."
      echo "The full \`$(basename "$log")\` is still uploaded as an artifact when \`upload-artifact\` is enabled."
      echo
      echo '<sub>Produced by <a href="https://github.com/stacktale/stacktale">stacktale</a></sub>'
    } > "$out"
    echo "st-json/1 is not yet supported by this action without jq — the log is still uploaded as an artifact" >&2
    echo 0
    exit 0
  fi

  total=$(count_st_json_reports)
  echo "$total"
  if [ "$total" -eq 0 ]; then
    exit 0
  fi
  summarize_st_json "$total"
  exit 0
fi

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
