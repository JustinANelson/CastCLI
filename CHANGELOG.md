# Changelog

All notable changes to CastCLI are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning intent is described in
[CONTRIBUTING.md](CONTRIBUTING.md#versioning).

## [Unreleased]

### Added
- `cast-cli doctor --check-updates`: opt-in, single network call comparing the running build
  against the latest GitHub release. Never runs automatically.
- `--version` now reports the actual build version (from the release jar's manifest) instead of a
  hardcoded string that could drift from `build.gradle.kts`.
- Cleaner CLI error output on uncaught exceptions: a one-line message by default, full stack trace
  when `CASTCLI_DEBUG=1` is set.
- macOS x64 release asset alongside the existing macOS arm64 build.
- `--config` (when left at its default, relative value) now searches upward from the current
  directory for `config/harness.local.json`, the way git/npm discover their config from a
  subdirectory -- `ask`/`doctor`/etc. no longer require running from the exact directory that
  holds `config/`. Absolute `--config` paths and `init`'s write target are unaffected.

### Changed
- Release workflow now runs the test suite on every platform matrix job (previously only on the
  Linux `release` job), and derives the packaged version from the pushed tag.
- `RateLimiterGuard`'s per-provider rate is now sourced from `reliability.maxRequestsPerMinute`
  instead of being hardcoded, matching the standalone `CostBudgetGuard` cost/rate limits that were
  folded into `ReliabilityExecutor.checkBudgetLimits` (no behavior change to the cost caps
  themselves -- `maxCostUsdPerTask`/`maxCumulativeCostUsd` enforcement is unchanged, just no longer
  in a separate class).

## [0.1.0] - pre-1.0

Everything up to this point: routing across local/frontier providers, hierarchical multi-agent
commissioning, MCP client/server support, durable shared memory (SQLite), OpenTelemetry tracing,
reliability guardrails (retry/circuit-breaker/cost budgets), human-in-the-loop approval gating,
hardware-preset `init`, and native-bundle (`jpackage`) releases for Windows/Linux/macOS. See
`git log` for the detailed history — no tagged releases existed prior to this file.
