# Using the stacktale MCP server

`stacktale-mcp` lets an AI assistant read your app's error reports as tools — `list_errors`,
`get_report`, `errors_since` — and, with a subscription, be **notified the moment a new
error lands** instead of polling. It's a tiny read-only server that speaks
[MCP](https://modelcontextprotocol.io) over stdio. No network, no writes.

## Get the jar

It's on Maven Central. Three ways, easiest first:

**JBang** (zero install, if you have JBang):

```bash
jbang run io.github.gabrielbbaldez:stacktale-mcp:0.4.0 --file /path/to/errors-ai.log
```

**Download the jar directly** (curl):

```bash
curl -L -o stacktale-mcp.jar \
  https://repo1.maven.org/maven2/io/github/gabrielbbaldez/stacktale-mcp/0.4.0/stacktale-mcp-0.4.0.jar
```

**Maven** (into a folder you choose):

```bash
mvn dependency:copy \
  -Dartifact=io.github.gabrielbbaldez:stacktale-mcp:0.4.0 \
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

## Troubleshooting

- **"command not found: java"** — the server runs on the JVM; Java must be on the `PATH`
  the client launches with. On Windows, use forward slashes in paths or double backslashes.
- **Nothing shows up** — always use **absolute** paths for both the jar and the log file;
  MCP clients don't launch from your project directory.
- **No live notifications** — subscriptions require a client that supports MCP resource
  subscriptions; tools (`list_errors` etc.) work everywhere.
