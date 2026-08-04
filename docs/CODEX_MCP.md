# Verifying Codex delegation to CastCLI

## Configure

1. Enable mcpAudit in the local harness config. It defaults to enabled and writes
   .cast/metrics/mcp-usage.jsonl, which is covered by the repository's .gitignore.
2. Run ./gradlew.bat installDist.
3. Copy config/codex-mcp.example.toml into the applicable Codex config.toml, replace its absolute paths,
   restart Codex, and run /mcp.
4. Confirm cast-cli is connected and exposes `ask_local`, memory tools, structured delegation tools, and coordination tools (`coordination_snapshot`, `set_project_state`, `create_coordination_task`, `claim_coordination_task`, `heartbeat_coordination_task`, and `handoff_coordination_task`).

The checked-in AGENTS.md establishes mandatory delegation triggers (`generate_tests`, `review_diff`, `map_change_impact`, `analyze_failure`) for bounded, read-only work, while excluding security, credentials, destructive work, production operations, and final verification. Agents are also instructed to load `coordination_snapshot`, atomically claim tasks before editing, heartbeat long-running work, and release leases through structured handoffs. Session summaries remain supplementary history.

## Verify utilization

Each MCP tool result has an invocation ID and audit-file location in its MCP _meta. A delegated result also
ends with a compact receipt containing provider, model, local token count, estimated cost, and trace ID.
The durable audit includes successful and failed calls even when OpenTelemetry export is disabled.

The report shows an **estimated frontier-equivalent cost** when a frontier reference is available. It is
populated in two ways (in priority order):

1. **`_meta.callerModel` per tool call** â€” the AGENTS.md policy instructs every frontier agent to include
   `"_meta": {"callerModel": "<model-name>"}` alongside `arguments` in each cast-cli tool call. The server
   records it on each audit entry, and `mcp-usage` resolves pricing from the harness config (matching by
   `modelName`, case-insensitive) or falls back to the configured FRONTIER_CLOUD provider.
2. **Configured FRONTIER_CLOUD reference provider** â€” add an enabled provider with `"tier": "FRONTIER_CLOUD"`
   and non-zero `costPerMillionInputTokens`/`costPerMillionOutputTokens` in the harness config.


    ./gradlew.bat run --args="--config config/harness.local.json mcp-usage --since-hours 24 --fail-if-unused"

The report shows total MCP calls, delegation attempts and successes, `ask_local` calls, local input/output
tokens, estimated local cost, calls by tool/provider, and an estimated frontier-equivalent cost when the config has
an enabled FRONTIER_CLOUD reference provider. Per-tool performance includes p50/p95 latency plus timeout,
context-rejection, and direct-fallback counts.

Structured delegations reserve 30% of `routing.maxContextChars` for model output and protocol overhead before
retrieving workspace content. File reads stop at the partition budget; common generated roots (`build`,
`.gradle`, `out`, `target`, and `node_modules`) are skipped. Search lines are capped, inputs are packed into at most four bounded partitions,
and multi-part summaries use one bounded reduction pass. Results are capped at 4,000 characters. Each MCP
delegation has a total deadline (`reliability.mcpDelegationDeadlineSeconds`, default 60 seconds) and one
local-provider attempt; a failure returns `castcli/fallbackRecommended=true`. If `mcp-usage` shows
`timeouts`/`fallbacks` consistently for `map_change_impact`, `generate_tests`, or `summarize_files` on a
large repo or with a slower local model, raise `mcpDelegationDeadlineSeconds` in the harness config.
Identical failed requests are suppressed for five minutes and return `castcli/retrySuppressed=true`,
telling the caller to continue directly instead of repeating slow work.

For automation:

    ./gradlew.bat run --args="--config config/harness.local.json mcp-usage --since-hours 1 --json --fail-if-unused"

## Compare efficiency

Enabling an MCP server does not prove Codex saves tokens: Codex still spends tokens selecting the tool and
reading its result. Measure equivalent tasks in fresh sessions with the same model, reasoning effort, prompt,
and repository state:

1. Baseline session: tell Codex not to use CastCLI and record the session/turn token count from /status or
   Codex OpenTelemetry.
2. Delegated session: allow the policy in AGENTS.md, confirm an ask_local receipt, and record Codex tokens.
3. Pass both measurements to the report:

       ./gradlew.bat run --args="--config config/harness.local.json mcp-usage --since-hours 1 --baseline-codex-tokens 12000 --delegated-codex-tokens 7800"

The report separates measured Codex token savings from local-model tokens and also shows combined processing
tokens. Repeat at least three times and compare medians; validate answer quality alongside token and latency
changes.

Codex documents /mcp as the connected-server/tool status surface and /status as the session token-usage
surface. For higher-fidelity experiments, Codex OpenTelemetry emits request, response-completion token, tool
decision, and tool-result events:

- https://learn.chatgpt.com/docs/extend/mcp
- https://learn.chatgpt.com/docs/config-file/config-advanced

## Coordinated multi-agent workflow

1. Call `coordination_snapshot` before selecting work.
2. Establish or version-update canonical project state with `set_project_state`.
3. Create tasks with dependencies and expected files, then atomically claim one with a bounded lease.
4. Reconcile any file-overlap warnings before editing and heartbeat work that outlives its lease window.
5. Record a structured handoff with exact files, verification, failures, next action, and commit/diff reference.

SQLite WAL, immediate transactions, and optimistic versions prevent double claims and lost project-state updates
across local MCP processes. Expired leases can be taken over and are surfaced as warnings in snapshots.