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

When the CastCLI MCP server is available, actively look for eligible subtasks during each coding turn. Delegate bounded, read-only, low-risk work when verification is materially cheaper than producing the result directly.

### Mandatory Delegation Triggers

Agents MUST invoke CastCLI MCP tools for the following subtasks before proceeding with direct frontier execution:

1. **Unit Test Generation (`generate_tests`)**:
   - **Trigger**: When adding a new Java class or creating a new feature method.
   - **Action**: Call `generate_tests` with the target file path to draft unit test skeletons on `LARGE_LOCAL` before writing test files.

2. **Patch & Diff Review (`review_diff`)**:
   - **Trigger**: Before running `.\gradlew.bat test` or `.\gradlew.bat check`.
   - **Action**: Call `review_diff` with the target path to inspect syntax and catch missing imports on `LARGE_LOCAL`.

3. **Multi-File Impact Analysis (`map_change_impact` / `summarize_files`)**:
   - **Trigger**: When modifying interfaces, core records, or multi-file dependencies.
   - **Action**: Call `map_change_impact` or `summarize_files` to evaluate impacted files before making edits.

4. **Failure Analysis (`analyze_failure`)**:
   - **Trigger**: When a build task or unit test fails with an exception or stack trace.
   - **Action**: Call `analyze_failure` with the error output to extract root cause recommendations on `SMALL_LOCAL`.

### Delegation Rules

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

## Multi-agent and multi-session coordination

For substantial work or any handoff between agents/roles:
- Start with `coordination_snapshot` to load the canonical objective, phase, decisions, blockers, active tasks,
  leases, and recent structured handoffs. Then use `recall_session_memory` or `recall_context` for deeper history.
- Create a dependency-aware task with `create_coordination_task`, declaring expected files before editing. Claim it
  atomically with `claim_coordination_task`; treat lease conflicts as ownership boundaries and overlap warnings as
  a prompt to coordinate before touching the reported files.
- Renew long work with `heartbeat_coordination_task`. A lease is coordination metadata, not permission to overwrite
  unrelated user changes; continue to inspect the worktree and preserve concurrent edits.
- End owned work with `handoff_coordination_task`, recording status, files changed, tests run, failures, next action,
  and commit/diff reference. Use `OPEN` for transferable work, `BLOCKED` for an explicit blocker, or `COMPLETE` only
  after verification. The handoff releases the lease.
- Update canonical project state through `set_project_state` using its current version. Version conflicts require a
  fresh snapshot and reconciliation; never blindly overwrite another agent's newer decisions.
- Use `summarize_session` for supplementary narrative history. Structured coordination state is authoritative for
  current ownership and status; free-form memory is not a task lock.

## Persistent work checklist

Maintain `docs/WORK_CHECKLIST.md` for multi-step work, work likely to span turns, or any turn ending with unfinished
work. When required, create or update it after scoping and again before ending the turn. Keep only the active
objective, Markdown-checkbox steps, blockers or open decisions, next concrete action, relevant paths, and
verification status. Do not update it for read-only questions or trivial completed tasks. Mark work complete only
after verification, and remove or archive stale entries.

## Reporting

Report decisions, material results, blockers, and verification outcomes. Omit narration of routine searches,
reads, and successful commands.
