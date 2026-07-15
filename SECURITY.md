# Security Policy

stacktale writes a second log file (`errors-ai.log`) meant to be read by an automated
consumer — an AI assistant or agent. Because tools read it, its contents deserve deliberate
thought. This is the security posture and the vulnerability-reporting process.

## Reporting a vulnerability

Please **do not** open a public issue for a security problem. Email
<gabrielbaldez2009@gmail.com> with details and steps to reproduce; you'll get an
acknowledgement within a few days. Once a fix is available it is released and credited to
you (if you'd like).

## Posture & threat model

**No network, no exfiltration.** stacktale only appends to a local file. It opens no
sockets, makes no callbacks, and sends nothing anywhere. The optional `stacktale-mcp` server
is **read-only** and speaks JSON-RPC over **stdio** — there is no network listener.

**Redaction is on by default** — know its edges:

- **Masks:** values of secret-named keys (including compound keys like `db.password`,
  `x-api-key`), secret-*shaped* values (high-entropy tokens, `Bearer …`, private-key
  blocks), and message arguments at positions your patterns mark.
- **Can't know:** a secret in an unusual shape under a benign name. Redaction is a strong
  default, not a guarantee.
- **Harden it:** add your own `redactPattern`s; set `captureExceptionFields=false` to stop
  reading exception getters into `fields:` (recommended if your domain exceptions carry
  sensitive state); weigh `redactionCorrelation` (a keyed hash of masked values — see
  [docs/FORMAT.md](docs/FORMAT.md)) against your threat model.
- **Verify before you trust it:** in **dev**, read `errors-ai.log` and confirm nothing
  sensitive leaks before wiring it near prod. (A redaction self-audit tool is planned.)

**What ends up in the file** (by design): the distilled exception chain, the log message and
args, MDC, exception field values (unless disabled), the recent story of the same
request/thread, and one environment line (app version, git sha, Java, profile, OS). Your
normal logs are untouched.

**Single writer.** `errors-ai.log` assumes one writing process. Two JVMs sharing a file can
race on rotation — run one writer per file, or give each instance its own path.

**Supply chain.** `stacktale-core` has one runtime dependency (`slf4j-api`). The agent shades
ByteBuddy (relocated under an internal package). Releases are GPG-signed and published to
Maven Central.

## Supported versions

Security fixes land on the latest released minor line. Please run a current version.
