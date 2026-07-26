---
name: fix-loop
description: Use when a Java app that has stacktale installed is failing — after running it or its tests, to find what broke, fix it, and confirm the fix by re-running and checking for new errors. Also use when asked to "check the errors", "what's failing", or to work through errors-ai.log.
version: 1.0.0
---

# Working an error down to zero with stacktale

stacktale writes `errors-ai.log` next to the app's normal logs. Each entry is a complete
report: the root cause first, the app's own culprit frame marked `← YOUR CODE`, the log
lines that led up to the failure, the values involved, and the environment. It is written
for you to read — you should not need to ask the user for a stack trace.

## The loop

`errors_since_last_check` is the whole point. It keeps a cursor per session, so:

1. **Take a baseline.** Call `errors_since_last_check` once. The first call reports what is
   currently on file — that is your starting position, not new breakage.
2. **Read the top error properly.** The headline is the *root* cause, not the outermost
   wrapper. Go to the frame marked `← YOUR CODE`; that is the app's own code, and it is
   almost always where the fix belongs. Read the `story` before deciding — it usually
   contains the line that explains *why* the value was wrong, which the stack trace alone
   never shows.
3. **Fix it.**
4. **Re-run** the app, or its tests if `stacktale-junit` is on the test classpath (a failing
   test writes a report too).
5. **Call `errors_since_last_check` again.** It reports 🆕 for errors that are new since
   your last call, 🔁 for ones still occurring, and `✓ No new errors` when the run is clean.
6. Repeat from 2 until clean.

Do not re-read the whole file between rounds. The cursor is there so you only look at what
changed.

## The other tools

- `list_errors` — the most recent reports, newest first. Good for orienting yourself.
- `get_report` — one report in full, by id.
- `errors_since` — everything after a timestamp, when you want a specific window rather
  than the cursor.
- `find_similar_errors` — has this failure happened before? Useful before assuming a fix is
  novel.
- `match_report` — the user pasted a bare stack trace: this finds the captured report for
  it, with the story and values the paste is missing. Reach for it whenever a trace arrives
  without context.

## Reading the reports well

- **`🔁 still occurring` after a fix means the fix did not take.** Check whether the app
  actually restarted and picked up your change before assuming the diagnosis was wrong.
- **A repeat line (`━ #id repeated N× ━`) is a frequency signal.** An error occurring
  hundreds of times is usually the one to fix first, even if it is not the newest.
- **`seen: N× this session`** distinguishes a new failure from a long-standing one.
- **Don't trust a wrapper's message over the root cause.** stacktale already put the root
  cause on the headline; the `wrapped by:` chain is context, not the diagnosis.
- If the story is empty, the app logged nothing before failing. Say so rather than
  inventing a cause — and consider suggesting a log line at the decision point that went
  wrong.

## When there is no log

If the tools return nothing and no `errors-ai.log` exists, the app either has not failed
yet or does not have stacktale installed. Setup is one dependency:
https://github.com/stacktale/stacktale#quickstart — the Spring Boot starter needs no
configuration at all.
