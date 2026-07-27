# CastCLI Operations & Deployment Runbook

This guide details operational guidelines, security sandboxing standards, deployment patterns, monitoring, and disaster recovery procedures for **CastCLI**.

Most CastCLI users are running it solo, on one machine, against local Ollama models — not operating
it as a hosted service. If that's you, **Section 0** is everything you need. Sections 1-5 are a
deeper runbook for anyone deploying CastCLI as a shared/hosted service (a team MCP server, a
container behind CI, etc.) — skip them unless that applies to you.

---

## 0. Solo/Indie Quick Checklist

- **Install**: grab the bundle for your OS from the
  [latest release](https://github.com/JustinANelson/CastCLI/releases/latest) — no JDK
  required, it embeds its own runtime — or `docker run` the published image. See the
  [README quick start](../README.md#quick-start).
- **First run**: `cast-cli init` detects your GPU/VRAM (or Apple Silicon) and writes a matching
  config; it also tells you which `ollama pull` commands you still need to run.
- **Sanity check before trusting it**: `cast-cli doctor` (add `--json` for scripting) checks the
  workspace path, provider keys/endpoints, memory database integrity, and the semantic index in one
  shot. Run it after any config change. Add `--check-updates` to also compare your build against
  the latest GitHub release (one network call; CastCLI never checks for updates unless you ask).
- **Never put secrets in config files.** API keys are read only from environment variables named by
  `apiKeyEnv` (e.g. `OPENAI_API_KEY`). `cast-cli config validate` warns if a referenced env var isn't
  set.
- **Back up what matters**: the only state worth protecting is `.cast/memory/memory.db` (durable
  memory) and `config/harness.local.json` (your config, if you hand-edited it after `init`). Both are
  plain files — copy them wherever you back up other local project state. `.cast/` is gitignored by
  default; keep it that way, since it can contain remembered project context and an audit trail of
  approved actions.
- **Approvals**: by default, writes and shell execution require interactive confirmation
  (`ConsoleApprovalGate`). Nothing runs on disk without you seeing it first unless you deliberately
  configure automated approval.
- **If it crashes**: a redacted stack trace is written to `.cast/crashes/` automatically -- local
  only, never uploaded. Attach it to a bug report if you file one.

---

## Appendix: Hosted/Team Deployment Runbook

The sections below are for running CastCLI as a shared service (team MCP server, CI-driven
container, etc.) rather than a local solo install.

## 1. Deployment Topologies

### Local CLI & Harness Deployment
- Build distribution package:
  ```bash
  ./gradlew installDist
  ```
- Run local harness:
  ```bash
  ./build/install/cast-cli/bin/cast-cli ask --prompt "Explain the architecture of this repository"
  ```

### Docker Container Deployment
- CastCLI provides a multi-stage Dockerfile with non-root security execution (`user: castcli`).
- Build container:
  ```bash
  docker build -t cast-cli:latest .
  ```
- Run MCP stdio server container:
  ```bash
  docker run -i --rm --name cast-cli-mcp cast-cli:latest mcp-serve
  ```
- Health probe is built into the container image:
  ```bash
  docker inspect --format='{{json .State.Health}}' cast-cli-mcp
  ```
- Prefer not to install/run Ollama on the host? `docker-compose.yml` brings up an `ollama` container
  alongside `cast-cli` and points it there automatically (`OLLAMA_BASE_URL=http://ollama:11434/v1/`):
  ```bash
  docker compose up -d
  docker compose exec ollama ollama pull qwen3.5:9b   # pull whatever models your config references
  docker compose run --rm cast-cli cast-cli doctor
  ```
  (`run --rm` rather than `exec`: the `cast-cli` service runs `mcp-serve`, an stdio server with no
  attached stdin, so it isn't a long-lived shell target to `exec` into.)

---

## 2. Health Probes & Diagnostics

CastCLI provides a machine-readable health diagnostic subcommand used for Kubernetes liveness/readiness probes and automated container healthchecks:

```bash
cast-cli doctor --json
```

### Key Diagnostic Checks:
1. **Workspace Root**: Validates directory exists and is writable.
2. **Provider Key Status & Endpoints**: Tests baseline API key environment configuration and HTTP endpoint reachability.
3. **Shared Memory Database (`.cast/memory/memory.db` by default, or `memory.databasePath` from config)**: Performs SQLite integrity check (`PRAGMA integrity_check`) and schema version verification.
4. **Semantic Embedding Index (`.cast/index/workspace-embeddings.json` by default, or `embeddings.indexPath` from config)**: Checks index file size and accessibility.
5. **System Storage & Memory**: Monitors partition disk space and JVM memory bounds.

---

## 3. Security & Sandboxing Guidelines

### Process & Tool Execution
- [ProcessExecTool](file:///c:/Users/justnels/Projects/CastCLI/src/main/java/dev/justnels/castcli/tools/ProcessExecTool.java) requires explicit configuration approval:
  ```json
  "tools": {
    "allowShellExec": true,
    "allowWrites": false
  }
  ```
- Tool execution is additionally restricted by an [ApprovalGate](file:///c:/Users/justnels/Projects/CastCLI/src/main/java/dev/justnels/castcli/tools/ApprovalGate.java) (`ConsoleApprovalGate` in interactive CLI mode, `AutoApprovalGate` in automated library mode).
- *Production Environment Requirement*: Run CastCLI containers with read-only root filesystems and restricted volume mounts, using gVisor or standard Docker user namespace remapping.

### Secret Management & Governance
- Never commit API keys or sensitive endpoints to configuration files.
- Inject keys dynamically using environment variables (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`) or secret integration tools (e.g. `aws secretsmanager` or HashiCorp Vault agent sidecars).
- All secret patterns are redacted automatically from telemetry traces and audit logs via [SecretRedactor](file:///c:/Users/justnels/Projects/CastCLI/src/main/java/dev/justnels/castcli/observability/SecretRedactor.java).

---

## 4. Observability & Audit Logging

### OpenTelemetry (OTLP) Tracing
Enable OTLP metric and span exporting in `config/harness.local.json`:
```json
"observability": {
  "enabled": true,
  "serviceName": "cast-cli-production",
  "jsonlPath": ".cast/traces/spans.jsonl",
  "otlpEnabled": true,
  "otlpEndpoint": "http://otel-collector:4318"
}
```

### Audit Log Inspection
Audit records are written to `.cast/audit.jsonl` with automatic file rotation at 10MB limit. Monitor for security approval events:
```bash
grep '"eventType":"SECURITY_APPROVAL"' .cast/audit.jsonl
```

---

## 5. Memory Database Maintenance & Disaster Recovery

- **Location**: `.cast/memory/memory.db` by default (`memory.databasePath` in config; SQLite database operating under Write-Ahead Logging `WAL` mode).
- **Online Backup**: Use the built-in backup command, or standard SQLite online backup, to create consistent snapshots without halting server processes:
  ```bash
  cast-cli memory vacuum --backup-path .cast/backups/memory.db.bak
  # or directly:
  sqlite3 .cast/memory/memory.db ".backup .cast/backups/memory.db.bak"
  ```
- **Corruption Recovery**: If `cast-cli doctor` flags database corruption, run schema re-initialization or restore from the latest `.bak` snapshot.

## 6. Compatibility & Migration Policy

CastCLI is pre-1.0 (see `CHANGELOG.md`); this policy describes the current behavior, not a
stability guarantee -- expect config keys and schema versions to still move as the project
approaches 1.0.

- **Config file (`config/harness.local.json` and friends)**: loaded by `ConfigLoader` with no
  version field and no migration step. New optional fields are additive and safe to leave unset.
  Renamed or removed fields are breaking -- check `CHANGELOG.md` under `[Unreleased]`/the release
  you're upgrading past before updating, and run `cast-cli config validate` and `cast-cli doctor`
  immediately after upgrading to catch a stale key early. There is currently no automated migration
  for config; presets in `config/` (e.g. `harness.vram-8gb.json`) are regenerated by `cast-cli init`
  and are the fastest way to get a known-good file if your hand-edited config falls out of sync with
  a new release.
- **Memory database (`.cast/memory/memory.db`)**: versioned via `SqliteMemoryMigrator`
  (`schema_version` table, currently `CURRENT_VERSION = 1`). Migrations are forward-only, additive
  (`CREATE TABLE IF NOT EXISTS` / new indexes), and run automatically on open -- there is no
  down-migration path. If a migration fails partway through, the safest recovery is restoring the
  most recent backup (Section 5) rather than attempting to hand-edit `schema_version`. Because
  migrations are irreversible, **always take a backup before upgrading across a release that bumps
  `CURRENT_VERSION`** (called out in the release's changelog entry):
  ```bash
  cast-cli memory vacuum --backup-path .cast/backups/pre-upgrade.db.bak
  ```
- **Crash reports**: on an uncaught CLI exception, a redacted stack trace is written to
  `.cast/crashes/` (local file only -- CastCLI never transmits it). Useful to attach to a GitHub
  issue; safe to delete anytime.
