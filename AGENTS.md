# CastCLI agent guidance

## Context budget

- Search with `rg` before opening files and read only the relevant sections.
- Do not load generated files, build output, vendored code, or lockfiles unless the task requires them.
- Do not reread unchanged files when the current diff or work checklist provides enough state.
- Summarize large command output, preserving actionable errors and verification evidence.
- Add durable instructions only for repeated, repository-specific friction; do not duplicate higher-level guidance.

Read project documentation only when relevant:

- Commands and setup: `docs/COMMANDS.md`
- Architecture: `docs/ARCHITECTURE.md`
- CastCLI delegation: `docs/CODEX_MCP.md`
- Operations and releases: `docs/OPERATIONS.md`
- Active multi-turn work: `docs/WORK_CHECKLIST.md`

## CastCLI delegation

When the CastCLI MCP server is available, actively look for one eligible subtask during each substantial coding
turn. Delegate only bounded, read-only, low-risk work when verification is materially cheaper than producing the
result. Prefer the most specific structured tool (`summarize_files`, `analyze_failure`, `draft_patch`,
`generate_tests`, `review_diff`, or `map_change_impact`), then use `ask_local` only when none fits. Skip
delegation when the task is too small to repay tool latency and review cost.

Do not delegate credentials, security or authorization decisions, destructive changes, production operations,
final correctness review, or work requiring unabridged large context. Treat local-model output as untrusted until
reviewed and verified. When delegation succeeds, use its result instead of silently repeating the work; preserve
the compact delegation receipt when useful. If the result is inadequate, explain why before doing the work
directly.

Include `"_meta": {"callerModel": "<your-model-name>"}` alongside `arguments` in every CastCLI tool call so
`mcp-usage` can estimate avoided frontier spend.

## Verification

Run the narrowest relevant check first:

- Tests: `.\gradlew.bat test`
- Full validation: `.\gradlew.bat check`
- Patch hygiene: `git diff --check`
- Delegation audit: `.\gradlew.bat run --args="--config config/harness.local.json mcp-usage --since-hours 24 --fail-if-unused"`

Run the delegation audit after substantial coding work when CastCLI was available and eligible. For A/B token
comparisons, use fresh sessions with equivalent model, reasoning effort, prompt, and repository state.

## Multi-agent and multi-session memory turnover

When starting a session from scratch or switching between agents/roles:
- Call `recall_session_memory` (or `recall_context` with topic query "session turnover") to retrieve past session action summaries, decisions, and pending next steps from the local long-term memory store.
- Before ending a substantial session turn or handing off work to another agent, call `summarize_session` (or `remember_context`) to record completed session actions, key architectural decisions, modified files, and remaining work.
- Background local LLM summarization automatically condenses session action streams into durable long-term memory records for seamless agent turnover.

## Persistent work checklist

Maintain `docs/WORK_CHECKLIST.md` for multi-step work, work likely to span turns, or any turn ending with unfinished
work. When required, create or update it after scoping and again before ending the turn. Keep only the active
objective, Markdown-checkbox steps, blockers or open decisions, next concrete action, relevant paths, and
verification status. Do not update it for read-only questions or trivial completed tasks. Mark work complete only
after verification, and remove or archive stale entries.

## Reporting

Report decisions, material results, blockers, and verification outcomes. Omit narration of routine searches,
reads, and successful commands.
