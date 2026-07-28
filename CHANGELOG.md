# Changelog

All notable changes to CastCLI are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning intent is described in
[CONTRIBUTING.md](CONTRIBUTING.md#versioning).

## [Unreleased]

## [0.1.2] - 2026-07-28

### Added
- `gateway`: an OpenAI-compatible inbound HTTP gateway (`/v1/chat/completions`, `/v1/models`) with
  bearer-token authentication, so existing OpenAI-SDK clients can route through CastCLI by changing
  only their base URL (phase 1: non-streaming, no client-tool passthrough yet).
- `index`: an incremental semantic (embedding-based) workspace search index, persisted to disk and
  exposed as the `semantic_search_workspace` MCP tool -- unchanged files are skipped by content hash
  on rebuild, and `semanticSearchWorkspace` now auto-refreshes a stale index in the background
  instead of blocking the search call on a full rebuild.
- `mcp-usage`: reports MCP delegation utilization, estimated cost savings from local-model
  delegation, and optional Codex A/B token-usage comparisons.
- `init` now works from any working directory, including the standalone release zip/jpackage
  bundle: hardware presets are bundled on the classpath (`config/*.json` remains the git-tracked
  source of truth; `processResources` mirrors them into the jar), and any command auto-creates
  `.cast/harness.local.json` the first time it can't find a config, anchored at the nearest
  ancestor `.git` directory.
- `init` now detects AMD/other non-NVIDIA GPUs by matching the reported GPU name against the
  hardware presets' documented card list, instead of only trying `nvidia-smi`.
- `init` now substitutes an already-installed, compatible model (same base family and parameter
  size, any quantization/instruct variant) for a preset's default `modelName` when one exists,
  instead of always asking you to pull the exact preset tag.
- Default `--config` is now `.cast/harness.local.json` (previously `config/harness.local.json`),
  consistent with the existing `.cast/` convention for memory, traces, and MCP usage logs.

### Fixed
- `init`'s Ollama reachability check no longer reports "not reachable" for a running Ollama
  instance that just needed a moment to accept its first connection (added one retry after a short
  delay) -- and, more significantly, no longer always failed regardless of retries: it was probing
  a preset's still-templated `${OLLAMA_BASE_URL:...}` placeholder instead of the expanded URL.
- Release automation now uses GitHub's supported `macos-15-intel` runner, can rebuild native
  bundles for an existing immutable tag, and avoids emulating the platform-independent Java build.

## [0.1.1] - 2026-07-26

### Added
- `cast-cli doctor --check-updates`: opt-in, single network call comparing the running build
  against the latest GitHub release. Never runs automatically.
- `--version` now reports the actual build version (from the release jar's manifest) instead of a
  hardcoded string that could drift from `build.gradle.kts`.
- Cleaner CLI error output on uncaught exceptions: a one-line message by default, full stack trace
  when `CASTCLI_DEBUG=1` is set.
- macOS x64 release asset alongside the existing macOS arm64 build.
- Bash, zsh, and PowerShell completion output via `cast-cli completion --shell`.
- Dependency locking and checksum verification, Dependabot, dependency review, CodeQL scanning,
  CycloneDX SBOMs, release checksums, and GitHub artifact/container attestations.
- `--config` (when left at its default, relative value) now searches upward from the current
  directory for `config/harness.local.json`, the way git/npm discover their config from a
  subdirectory -- `ask`/`doctor`/etc. no longer require running from the exact directory that
  holds `config/`. Absolute `--config` paths and `init`'s write target are unaffected.

### Changed
- Release workflow now runs the test suite on every platform matrix job (previously only on the
  Linux `release` job), derives the packaged version from the pushed tag, and verifies container
  health before publishing.
- `RateLimiterGuard`'s per-provider rate is now sourced from `reliability.maxRequestsPerMinute`
  instead of being hardcoded, matching the standalone `CostBudgetGuard` cost/rate limits that were
  folded into `ReliabilityExecutor.checkBudgetLimits` (no behavior change to the cost caps
  themselves -- `maxCostUsdPerTask`/`maxCumulativeCostUsd` enforcement is unchanged, just no longer
  in a separate class).

### Fixed
- Process execution now drains output concurrently, enforces its timeout, bounds retained output,
  terminates child processes, and actually applies its sanitized environment.
- MCP stdio now negotiates supported protocol versions, validates initialization order, and returns
  protocol errors for malformed input instead of silently dropping requests.
- Health-server liveness no longer discloses dependency details; the server binds to loopback by
  default and requires an explicit `--bind` to expose it.
- Docker's health check now uses valid exec form, Compose keeps stdin open for the interactive CLI,
  and documented CLI/Compose commands and links now match the shipped interface.

### Security
- File tools always deny access to common credential files and the `.git`, `.cast`, and local
  harness configuration trees, including through enumeration and search.
- Missing approval gates now deny writes and process execution instead of auto-approving them.
- Repository-controlled child processes receive only a minimal allow-listed environment, preventing
  arbitrary provider tokens and other credentials from being inherited.
- GitHub Actions are pinned to immutable commit SHAs and release provenance is generated alongside
  checksums and SBOMs.

## [0.1.0] - pre-1.0

Everything up to this point: routing across local/frontier providers, hierarchical multi-agent
commissioning, MCP client/server support, durable shared memory (SQLite), OpenTelemetry tracing,
reliability guardrails (retry/circuit-breaker/cost budgets), human-in-the-loop approval gating,
hardware-preset `init`, and native-bundle (`jpackage`) releases for Windows/Linux/macOS. See
`git log` for the detailed history — no tagged releases existed prior to this file.
