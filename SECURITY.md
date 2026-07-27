# Security Policy

CastCLI executes model-directed tool calls against your local filesystem and, optionally, your
shell and cloud model providers. Treat it accordingly: review configuration before enabling
writes or shell execution, and keep API keys out of version control.

## Reporting a Vulnerability

Please report suspected vulnerabilities privately using
[GitHub's private security advisory form](../../security/advisories/new) for this repository,
rather than opening a public issue. Include:

- The affected version/commit and platform (OS, JDK version).
- Steps to reproduce, or a minimal config/prompt that triggers the issue.
- The impact you'd expect (e.g., path traversal outside `tools.workspaceRoot`, arbitrary command
  execution bypassing the `ProcessExecTool` allow-list, secret leakage into traces/audit logs).

You should receive an initial response within 5 business days. We'll work with you on a fix and
coordinated disclosure timeline before any public write-up.

## Threat Model Summary

CastCLI is designed to run under an operator who controls its configuration file and, in CLI
mode, approves each write/exec tool call interactively. Key boundaries:

- **Workspace confinement**: `WorkspaceTools` normalizes every path and rejects anything outside
  `tools.workspaceRoot`. It also unconditionally excludes `.git`, `.cast`, local harness config,
  environment files, private keys, and common credential filenames from reads, listings, searches,
  and writes. A bug that lets a path escape either boundary is a high-severity report.
- **Writes and shell execution are off by default** (`tools.allowWrites`, `tools.allowShellExec`)
  and are double-gated by an `ApprovalGate` even when enabled. `ProcessExecTool` only runs a fixed
  command allow-list, never an arbitrary shell string, and passes only a minimal allow-listed
  environment to children. Library callers that omit an approval gate fail closed. A bypass of
  these controls is high severity.
- **Diagnostics**: the HTTP health server binds to loopback by default. `/livez` reports only static
  liveness; `/health` and `/readyz` include operational details and should not be exposed to an
  untrusted network without an authenticating reverse proxy.
- **JShell is disabled by default** because it executes arbitrary JVM code with no OS-level
  sandbox. Do not report "JShell can run arbitrary code" as a vulnerability on its own — that is
  documented, opt-in behavior (see [Safety](README.md#safety)). Do report anything that enables it,
  or escapes its intended scope, without explicit configuration.
- **Frontier cloud isolation from MCP serving**: `mcp-serve` strips every `FRONTIER_CLOUD` provider
  before building its tool config, so nothing routed through the MCP server should be able to spend
  cloud API budget. A path that defeats this filtering is high severity.
- **Secrets**: API keys are read only from environment variables named by `apiKeyEnv`, never from
  config files. `SecretRedactor` scrubs known secret patterns from telemetry traces and audit logs.
  A secret pattern that reaches `.cast/traces/` or `.cast/audit.jsonl` unredacted is a valid report.

Out of scope: vulnerabilities that require an operator to hand-edit config to disable the above
protections (e.g., manually enabling `allowShellExec` with `--yes` against an untrusted prompt),
since that is an explicit, documented opt-in.

## Supported Versions

CastCLI is pre-1.0. Version 0.1.1 and the latest `main` receive security fixes; there is no separate
long-term-support branch yet. Please test against the latest supported build before reporting.
