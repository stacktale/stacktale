# stacktale report action

Turns a red build's `errors-ai.log` into a pull-request comment and a job summary, so a
reviewer — or a PR agent — reads the root cause instead of scrolling raw CI logs.

```yaml
- run: mvn -B verify
- if: always()          # the reports only exist when something failed
  uses: stacktale/stacktale/.github/actions/report@v1.1.0
```

Pin to a tag; `@main` also works if you want the latest.

That is the whole setup. It needs `pull-requests: write` on the job to comment:

```yaml
permissions:
  contents: read
  pull-requests: write
```

## Inputs

| Input | Default | |
|---|---|---|
| `file` | `errors-ai.log` | path to the log, relative to the workspace |
| `max-reports` | `3` | how many to inline; the rest stay in the artifact |
| `comment` | `true` | post/update the PR comment |
| `upload-artifact` | `true` | upload the whole log as `stacktale-errors` |
| `github-token` | `github.token` | token used to comment |

**Output:** `count` — the number of complete reports found, `0` when the log is absent.
Useful for gating later steps.

## Behaviour worth knowing

- **One comment per PR, edited in place.** A new comment on every push turns a long-running
  branch into a wall of stale reports.
- **Truncated blocks are dropped.** The writer appends the header, the session marker and
  each block separately, so a read can land mid-write.
  [FORMAT.md](../../../docs/FORMAT.md) requires a reader to discard a block whose `END`
  line has not arrived rather than show half a report.
- **A missing log is not a failure.** A green build has nothing to say, and the action
  exits quietly with `count=0`.
- **No runtime dependency for text logs.** The st/1 extractor is awk, so it works on any
  runner. **st-json/1** logs (`format=json`) are summarized with `jq` when it is on the
  PATH (GitHub-hosted runners include it). Without `jq`, the action prints a clear notice
  and still uploads the log as an artifact.

## Where the reports come from

The library writes them. A failing *test* only produces one if `stacktale-junit` is on the
test classpath — without it, a red `mvn test` writes nothing and this action has nothing to
show. See the [quickstart](../../../README.md#quickstart).
