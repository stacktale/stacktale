# stacktale plugin for Claude Code

Gives Claude Code direct access to your Java app's `errors-ai.log` — and a fix-loop it knows
how to run.

## Install

```
/plugin marketplace add stacktale/stacktale
/plugin install stacktale@stacktale
```

Requires [JBang](https://www.jbang.dev) on your PATH (`brew install jbangdev/tap/jbang`, or
see their install page). That is the only prerequisite — the server itself is fetched from
Maven Central on first run.

## What you get

**Six tools** over the report file, read-only, no network:

| Tool | |
|---|---|
| `errors_since_last_check` | the fix-loop primitive — what's 🆕 new, 🔁 still occurring, or `✓ clean` since your last call |
| `list_errors` | recent reports, newest first |
| `get_report` | one report in full, by id |
| `errors_since` | everything after a timestamp |
| `find_similar_errors` | has this failed before? |
| `match_report` | you pasted a bare stack trace — find its captured report, with the story |
| `repro_for` | a JUnit skeleton for one report, from the throw site's typed signature and arguments |

**A skill** that teaches Claude how to work the loop: baseline, read the marked culprit
frame, fix, re-run, ask what changed, repeat until clean.

## The log file

The server reads `errors-ai.log` in the directory Claude Code is working from. If yours
lives elsewhere, set `STACKTALE_FILE` to its absolute path.

You get that file by adding stacktale to your app — one dependency, and zero configuration
on Spring Boot. See the [quickstart](https://github.com/stacktale/stacktale#quickstart).

## Without the plugin

The MCP server works standalone too, in Cursor, Claude Desktop or anything else that speaks
MCP — see [docs/mcp-setup.md](../../docs/mcp-setup.md).
