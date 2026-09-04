# Using the stacktale MCP server

`stacktale-mcp` lets an AI assistant read and act on your app's error reports as tools —
list them (`list_errors`), pull a full report (`get_report`), filter by time
(`errors_since`), find similar past ones (`find_similar_errors`), **attach a pasted trace to
its captured report** (`match_report`), **turn one into a reproduction test**
(`repro_for`), **read the source at the culprit line** (`culprit_source`), **ask whether any
test names it** (`tests_covering`), **check the file for leaked credentials**
(`audit_redaction`), and **loop on new errors until they're gone**
(`errors_since_last_check`). With a subscription it's also **notified the moment a new error
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

`culprit_source` and `tests_covering` read your **sources**, which is a different location:
they search the working directory the client launched the server from. That is normally the
project root, and normally the same place the default report path resolves against — but a
client that starts elsewhere, or a log kept outside the project (`/var/log`, a container
mount), makes the two diverge. Point them with `--workspace /path/to/project` or
`STACKTALE_WORKSPACE`. Build output (`target`, `build`, `node_modules`, `.git`) is never
searched: a copy of a source file there answers for the wrong tree.

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
> *Show me the code that failed* — it calls `culprit_source`.
> *Is this path tested at all?* — it calls `tests_covering`.
> *Is this log safe to attach to the ticket?* — it calls `audit_redaction`.

`culprit_source` and `tests_covering` read the **working tree**, not the log. Source code is
deliberately not in `errors-ai.log`: that file is gitignored, redacted, uploaded to CI
artifacts, pasted into PR comments and sized for tokens. It is also stale — during a fix loop
the current source is the only correct answer, and the log may be days old. Both degrade with
an explanation when the frame names a file this tree does not have (a dependency, generated
code, another service sharing the log).

`tests_covering` is a name match rather than coverage, and says so in its own answer. The
valuable reply is the negative one: nothing naming the failing method means writing a
reproduction test rather than hunting for one that does not exist.

`audit_redaction` scans every report for values shaped like credentials the redactor did not
mask — AWS, GitHub, Stripe, Slack and Google keys, JWTs, private-key blocks, Authorization
headers. Redaction masks what it can recognise by context (`password=…`, a JSON `"token"`
member, a long hex run), so a credential sitting in an ordinary sentence has nothing around it
to recognise. The answer names the report and the line and **never the value**: it is going
into an assistant's context and a transcript, and quoting the secret would move it somewhere
new. A clean result is evidence, not proof — a secret with no recognisable shape cannot be
found this way.

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
