# Using the stacktale MCP server

`stacktale-mcp` lets an AI assistant read and act on your app's error reports as tools —
list them (`list_errors`), pull a full report (`get_report`), filter by time
(`errors_since`), find similar past ones (`find_similar_errors`), **attach a pasted trace to
its captured report** (`match_report`), **turn one into a reproduction test**
(`repro_for`), and **loop on new errors until they're gone** (`errors_since_last_check`). With a subscription it's also **notified the moment a new error
lands** instead of polling. It's a tiny read-only server that speaks
[MCP](https://modelcontextprotocol.io) over stdio. No network, no writes.

## Fastest path (2 minutes)

If you have [JBang](https://www.jbang.dev), there's nothing to download. From your
**project root** (so `$PWD` resolves the log path for you):

**Claude Code** — one command:

```bash
claude mcp add stacktale -- jbang run io.github.gabrielbbaldez:stacktale-mcp:1.1.0 --file "$PWD/errors-ai.log"
```

**Cursor** — drop this into `.cursor/mcp.json` (swap in your absolute path):

```json
{ "mcpServers": { "stacktale": {
    "command": "jbang",
    "args": ["run", "io.github.gabrielbbaldez:stacktale-mcp:1.1.0", "--file", "/abs/path/errors-ai.log"]
} } }
```

Then ask your assistant *"what errors has the app had recently?"*. That's it — the log
file doesn't even need to exist yet; the server picks it up when the first error lands.

The rest of this page covers downloading the jar directly (no JBang), the env-var option,
Claude Desktop, and troubleshooting.

## Get the jar

It's on Maven Central. Three ways, easiest first:

**JBang** (zero install, if you have JBang):

```bash
jbang run io.github.gabrielbbaldez:stacktale-mcp:1.1.0 --file /path/to/errors-ai.log
```

**Download the jar directly** (curl):

```bash
curl -L -o stacktale-mcp.jar \
  https://repo1.maven.org/maven2/io/github/gabrielbbaldez/stacktale-mcp/1.1.0/stacktale-mcp-1.1.0.jar
```

**Maven** (into a folder you choose):

```bash
mvn dependency:copy \
  -Dartifact=io.github.gabrielbbaldez:stacktale-mcp:1.1.0 \
  -DoutputDirectory=.
```

## Configure the file

The server reads one report file. Point it there with either:

- the `--file /path/to/errors-ai.log` argument, or
- the `STACKTALE_FILE` environment variable (cleaner in some client configs).

If neither is set it defaults to `errors-ai.log` in the working directory.

## Client setup

### Claude Code

Add it with one command from your project directory:

```bash
claude mcp add stacktale -- java -jar /abs/path/stacktale-mcp.jar --file /abs/path/errors-ai.log
```

Or edit `.mcp.json` in the project root:

```json
{
  "mcpServers": {
    "stacktale": {
      "command": "java",
      "args": ["-jar", "/abs/path/stacktale-mcp.jar", "--file", "/abs/path/errors-ai.log"]
    }
  }
}
```

### Claude Desktop

Edit `claude_desktop_config.json`
(macOS: `~/Library/Application Support/Claude/`, Windows: `%APPDATA%\Claude\`):

```json
{
  "mcpServers": {
    "stacktale": {
      "command": "java",
      "args": ["-jar", "C:/abs/path/stacktale-mcp.jar"],
      "env": { "STACKTALE_FILE": "C:/abs/path/errors-ai.log" }
    }
  }
}
```

Restart Claude Desktop after editing.

### Cursor

`.cursor/mcp.json` in the project (or the global equivalent):

```json
{
  "mcpServers": {
    "stacktale": {
      "command": "java",
      "args": ["-jar", "/abs/path/stacktale-mcp.jar", "--file", "/abs/path/errors-ai.log"]
    }
  }
}
```

## Try it

Once wired up, ask your assistant:

> *What errors has the app had recently?* — it calls `list_errors`.
> *Show me the full report for #c73cf755* — it calls `get_report`.
> *What broke since 11am?* — it calls `errors_since`.
> *Have we seen this NPE before?* — it calls `find_similar_errors`.
> *[paste a stack trace] — what does stacktale have on this?* — it calls `match_report`.
> *Write me a test that reproduces #c73cf755* — it calls `repro_for`.

`repro_for` answers with a JUnit skeleton built from the report's `repro:` seed: the throw
site's class, its method, the declared parameter types and the values the call was given. It
needs `repro=true` and `stacktale-agent`; without them it says so rather than guessing, and
tells you what to switch on. Values the report had to redact arrive as an explicit `TODO`
rather than as a literal — a masked string that compiles would reproduce a call that never
happened.

## The fix-loop

The point of `errors_since_last_check` is to let an assistant fix errors in a loop without
you copy-pasting each one. The assistant drives it:

1. Call `errors_since_last_check` once — it shows the errors currently on file (your starting
   point) and remembers where you are.
2. Make a fix, then **re-run the app or tests** (the assistant's own step — stacktale never
   runs anything).
3. Call `errors_since_last_check` again. It reports only what changed: **🆕 new** errors, or
   **🔁 still occurring** ones (a fix that didn't take). `get_report <id>` for the full block.
4. Repeat until it answers **✓ No new errors** — that's clean; the loop is done.

Drop this into your `CLAUDE.md` or `.cursorrules` so the assistant does it by default:

```markdown
When fixing a runtime error, use the stacktale MCP server as a feedback loop:
call `errors_since_last_check` to see the current errors, make a fix, re-run the
app/tests, then call `errors_since_last_check` again. Fix what comes back as new
or still-occurring (use `get_report <id>` for detail) and repeat until it reports
"No new errors". If I paste a raw stack trace, call `match_report` to pull the full
stacktale report for it before reasoning about the fix.
```

## Troubleshooting

- **"command not found: java"** — the server runs on the JVM; Java must be on the `PATH`
  the client launches with. On Windows, use forward slashes in paths or double backslashes.
- **Nothing shows up** — use **absolute** paths for both the jar and the log file when
  configuring the server by hand; most MCP clients don't launch from your project
  directory. (The [Claude Code plugin](../plugins/stacktale/README.md) is the exception —
  it inherits Claude Code's working directory, so the default relative `errors-ai.log`
  resolves on its own.)
- **No live notifications** — subscriptions require a client that supports MCP resource
  subscriptions; tools (`list_errors` etc.) work everywhere.
