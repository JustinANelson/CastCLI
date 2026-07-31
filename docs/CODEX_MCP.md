# Verifying Codex delegation to CastCLI

## Configure

1. Enable mcpAudit in the local harness config. It defaults to enabled and writes
   .cast/metrics/mcp-usage.jsonl, which is covered by the repository's .gitignore.
2. Run ./gradlew.bat installDist.
3. Copy config/codex-mcp.example.toml into the applicable Codex config.toml, replace its absolute paths,
   restart Codex, and run /mcp.
4. Confirm cast-cli is connected and exposes `ask_local`, `remember_context`, `recall_context`, `summarize_session`, `recall_session_memory`, plus the structured delegation tools.

The checked-in AGENTS.md establishes mandatory delegation triggers (`generate_tests`, `review_diff`, `map_change_impact`, `analyze_failure`) for bounded, read-only work, while excluding security, credentials, destructive work, production operations, and final verification. Agents are also instructed to call `recall_session_memory` on session start and `summarize_session` before handoffs to maintain long-term memory turnover.

## Verify utilization

Each MCP tool result has an invocation ID and audit-file location in its MCP _meta. A delegated result also
ends with a compact receipt containing provider, model, local token count, estimated cost, and trace ID.
The durable audit includes successful and failed calls even when OpenTelemetry export is disabled.

The report shows an **estimated frontier-equivalent cost** when a frontier reference is available. It is
populated in two ways (in priority order):

1. **`_meta.callerModel` per tool call** — the AGENTS.md policy instructs every frontier agent to include
   `"_meta": {"callerModel": "<model-name>"}` alongside `arguments` in each cast-cli tool call. The server
   records it on each audit entry, and `mcp-usage` resolves pricing from the harness config (matching by
   `modelName`, case-insensitive) or falls back to the configured FRONTIER_CLOUD provider.
2. **Configured FRONTIER_CLOUD reference provider** — add an enabled provider with `"tier": "FRONTIER_CLOUD"`
   and non-zero `costPerMillionInputTokens`/`costPerMillionOutputTokens` in the harness config.


    ./gradlew.bat run --args="--config config/harness.local.json mcp-usage --since-hours 24 --fail-if-unused"

The report shows total MCP calls, delegation attempts and successes, `ask_local` calls, local input/output
tokens, latency, estimated local cost, calls by tool/provider, and an estimated frontier-equivalent cost when the config has
an enabled FRONTIER_CLOUD reference provider.

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
