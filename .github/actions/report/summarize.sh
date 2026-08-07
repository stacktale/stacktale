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

trim_line() {
  local line="$1"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  printf '%s' "$line"
}

first_non_blank_line() {
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    line=$(trim_line "$line")
    [ -n "$line" ] && { printf '%s' "$line"; return 0; }
  done < "$log"
  return 1
}

looks_like_json_log() {
  local first
  first=$(first_non_blank_line || true)
  [ -n "$first" ] || return 1
  [[ "$first" == \{* ]]
}

is_st_json_log() {
  local first
  first=$(first_non_blank_line || true)
  [ -n "$first" ] || return 1
  [[ "$first" == \{* ]] || return 1
  if command -v jq >/dev/null 2>&1; then
    if echo "$first" | jq -e '.format == "st-json/1"' >/dev/null 2>&1; then
      return 0
    fi
  fi
  [[ "$first" == *"st-json/1"* ]]
}

write_st_json_unsupported_notice() {
  {
    echo "### stacktale report"
    echo
    echo "This log is **st-json/1** (NDJSON). This action cannot summarize it without \`jq\`."
    echo "The full \`$(basename "$log")\` is still uploaded as an artifact when \`upload-artifact\` is enabled."
    echo
    echo '<sub>Produced by <a href="https://github.com/stacktale/stacktale">stacktale</a></sub>'
  } > "$out"
  echo "cannot summarize st-json/1 without jq — the log is still uploaded as an artifact" >&2
}

count_st_json_reports() {
  local count=0 line type
  while IFS= read -r line || [ -n "$line" ]; do
    line=$(trim_line "$line")
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
    local count=0 line type headline id ts
    while IFS= read -r line || [ -n "$line" ]; do
      line=$(trim_line "$line")
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

handle_st_json_log() {
  if ! command -v jq >/dev/null 2>&1; then
    write_st_json_unsupported_notice
    echo 0
    return 0
  fi

  local total
  total=$(count_st_json_reports)
  if [ "$total" -eq 0 ] && grep -q '"type"[[:space:]]*:[[:space:]]*"report"' "$log" 2>/dev/null; then
    {
      echo "### stacktale report"
      echo
      echo "This log looks like **st-json/1**, but the action could not parse it with \`jq\`."
      echo "The full \`$(basename "$log")\` is still uploaded as an artifact when \`upload-artifact\` is enabled."
      echo
      echo '<sub>Produced by <a href="https://github.com/stacktale/stacktale">stacktale</a></sub>'
    } > "$out"
    echo "could not parse st-json/1 with jq — the log is still uploaded as an artifact" >&2
    echo 0
    return 0
  fi

  echo "$total"
  if [ "$total" -eq 0 ]; then
    return 0
  fi
  summarize_st_json "$total"
}

if is_st_json_log; then
  handle_st_json_log
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
  if looks_like_json_log; then
    handle_st_json_log
  fi
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
