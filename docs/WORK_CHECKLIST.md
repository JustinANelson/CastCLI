# Work checklist

## Current objective

Implement background local LLM session action summarization and local long-term memory turnover in CastCLI, with agent instructions in AGENTS.md.

## Checklist

- [x] Obtain user review and approval for `implementation_plan.md`.
- [x] Implement `SessionAction` and `SessionMemorySummarizer` for background local LLM summarization and memory store turnover.
- [x] Enhance `MemoryTools` and `McpStdioServer` with `summarize_session` and `recall_session_memory` tools.
- [x] Integrate session memory recording and background summarization into `AgentTeam` and `CastCli`.
- [x] Add session turnover guidelines to `AGENTS.md` and update documentation (`docs/ARCHITECTURE.md`, `docs/CODEX_MCP.md`, `docs/COMMANDS.md`).
- [x] Add unit and integration tests (`SessionMemorySummarizerTest`, `McpStdioServerTest`) and verify with `.\gradlew.bat test` and `.\gradlew.bat check`.

## Blockers and open decisions

None.

## Next action

No follow-up required.
