# Work checklist

## Current objective

Resolve the MCP delegation latency, context-overflow, retry, output-size, and telemetry issues observed in the July 2026 implementation session.

## Checklist

- [x] Budget and partition delegation input before repository retrieval exceeds the local context window.
- [x] Bound search results, individual lines, logs, diffs, files, and delegated responses.
- [x] Add hierarchical summarization for multi-file requests.
- [x] Enforce a short total MCP delegation deadline and one provider attempt.
- [x] Suppress identical failed requests and signal direct-execution fallback.
- [x] Report per-tool p50/p95 latency, timeout, context-rejection, and fallback counts.
- [x] Add regression tests and document the new behavior and tuning limits.
- [x] Run focused tests, full validation, diff review, and the delegation audit.

## Blockers and open decisions

None. The installed distribution has been refreshed. Existing MCP processes must restart to load it.

## Next action

Restart the MCP client/server session so it launches the refreshed distribution.

## Relevant paths

- `src/main/java/dev/justnels/castcli/mcp/`
- `src/main/java/dev/justnels/castcli/tools/WorkspaceTools.java`
- `src/test/java/dev/justnels/castcli/mcp/`
- `src/test/java/dev/justnels/castcli/tools/`
- `docs/CODEX_MCP.md`

## Verification status

- Focused MCP delegation, deadline, retry-suppression, telemetry, and workspace-bound tests: passed.
- Forced full `check`: passed, including all tests and unchanged JaCoCo gates.
- Checkstyle: passed with one unrelated pre-existing warning in `JsonRawSchemaWireVerificationTest`.
- `git diff --check`: passed with expected Windows line-ending notices only.
- Delegation audit: passed and rendered the new per-tool p50/p95 and failure counters.
- `installDist`: refreshed successfully.
