#!/usr/bin/env bash
# Guard the example reports in the docs against shapes the renderer cannot produce.
#
# Three separate doc bugs turned out to be one bug: prose claiming output no
# StackDistiller run could emit, with nothing checking it. Full equality against
# stacktale-core/src/test/resources/golden/ is too strict — the docs use a
# different app name and shorter stacks on purpose — so this checks the
# properties that were actually wrong (FORMAT.md §3 is the reference):
#
#   1. a block opening "━━━ ERROR #<id>" closes with "━━━ END #<id>", same id
#   2. every "… N collapsed (a ×x, b ×y)" satisfies x + y + … == N
#   3. "story (…, last N events, …)" and "stack (distilled, N of M frames)"
#      keep their real shape, and N <= M for the stack
#   4. a block with a story also has an "env:" line — i.e. it is not truncated
#      mid-report while being introduced as a whole one
#
# On failure, fix the report in the named file. Do not adjust this script to
# match the docs: the whole point is that the docs match the renderer.
set -euo pipefail

cd "$(dirname "$0")/.."

files=(README.md)
[ -f docs/site/index.html ] && files+=(docs/site/index.html)

fail=0
for file in "${files[@]}"; do
  awk -v FILENAME_="$file" '
    # Last run of digits in s. Each part reads "<framework> ×<count>", and the
    # count is always last, so this gets it without matching the multiplication
    # sign — which is multibyte, and mawk (the default awk on the CI runner) is
    # byte-oriented. Also survives a framework whose name contains a digit,
    # which "first number wins" would not: "log4j2 ×5".
    function last_number(s,   found) {
      found = ""
      while (match(s, /[0-9]+/)) {
        found = substr(s, RSTART, RLENGTH)
        s = substr(s, RSTART + RLENGTH)
      }
      return found
    }

    # Rule 2 applies everywhere, not only inside fenced blocks: the table row and
    # the landing page carried the impossible sum in running prose.
    {
      line = $0
      while (match(line, /[0-9]+ collapsed \([^)]*\)/)) {
        chunk = substr(line, RSTART, RLENGTH)
        line = substr(line, RSTART + RLENGTH)

        match(chunk, /[0-9]+/)
        total = substr(chunk, RSTART, RLENGTH) + 0

        inner = chunk
        sub(/^[^(]*\(/, "", inner)
        sub(/\)$/, "", inner)

        sum = 0
        seen = split(inner, part, ",")
        for (i = 1; i <= seen; i++) sum += last_number(part[i]) + 0

        if (seen > 0 && sum != total) {
          printf "%s:%d: collapsed parts sum to %d but the total says %d -- %s\n", \
            FILENAME_, NR, sum, total, chunk
          bad = 1
        }
      }
    }

    /^[[:space:]]*```/ { in_fence = !in_fence }

    /━━━ ERROR #/ {
      if (match($0, /#[0-9a-f]+/)) {
        open_id = substr($0, RSTART + 1, RLENGTH - 1)
        open_line = NR
        saw_story = 0
        saw_env = 0
      }
      next
    }

    open_id != "" && /^story \(/ {
      saw_story = 1
      if ($0 !~ /^story \(.*last [0-9]+ events,.*\):$/) {
        printf "%s:%d: story header does not match \"story (…, last N events, …):\" — %s\n", \
          FILENAME_, NR, $0
        bad = 1
      }
      next
    }

    open_id != "" && /^stack \(/ {
      if ($0 !~ /^stack \(distilled, [0-9]+ of [0-9]+ frames\):$/) {
        printf "%s:%d: stack header does not match \"stack (distilled, N of M frames):\" — %s\n", \
          FILENAME_, NR, $0
        bad = 1
      } else {
        shown = $0; sub(/^stack \(distilled, /, "", shown); sub(/ of .*/, "", shown)
        total_frames = $0; sub(/^.* of /, "", total_frames); sub(/ frames\):$/, "", total_frames)
        if (shown + 0 > total_frames + 0) {
          printf "%s:%d: stack shows %d of %d frames — cannot distil to more than it had\n", \
            FILENAME_, NR, shown, total_frames
          bad = 1
        }
      }
      next
    }

    open_id != "" && /^env: / { saw_env = 1; next }

    /━━━ END #/ {
      close_id = ""
      if (match($0, /#[0-9a-f]+/)) close_id = substr($0, RSTART + 1, RLENGTH - 1)

      if (open_id == "") {
        printf "%s:%d: \"END #%s\" closes a block that was never opened\n", FILENAME_, NR, close_id
        bad = 1
      } else {
        if (close_id != open_id) {
          printf "%s:%d: block opened as #%s (line %d) but closes as #%s\n", \
            FILENAME_, NR, open_id, open_line, close_id
          bad = 1
        }
        if (saw_story && !saw_env) {
          printf "%s:%d: block #%s has a story but no \"env:\" line — truncated mid-report\n", \
            FILENAME_, NR, open_id
          bad = 1
        }
      }
      open_id = ""
      next
    }

    END {
      if (open_id != "") {
        printf "%s:%d: block #%s opens but never closes with \"END #%s\"\n", \
          FILENAME_, open_line, open_id, open_id
        bad = 1
      }
      exit bad
    }
  ' "$file" || fail=1
done

if [ "$fail" -ne 0 ]; then
  echo
  echo "Example reports in the docs disagree with the format the renderer produces."
  echo "See FORMAT.md §3, and stacktale-core/src/test/resources/golden/ for real output."
  exit 1
fi

echo "Docs example reports are consistent with the report format."
