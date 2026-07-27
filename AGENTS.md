# CastCLI delegation policy

When the CastCLI MCP server is available, prefer its structured delegation tools, then `ask_local`, for
bounded, read-only, low-risk work:

- summarization, classification, extraction, formatting, and naming;
- first-pass explanations of logs, stack traces, or documentation;
- draft test cases, release notes, comments, or implementation outlines;
- repository searches and other tasks whose result you can cheaply verify.

Use the most specific structured tool available:

- `summarize_files` for bounded code or documentation synthesis;
- `analyze_failure` for first-pass log, stack-trace, build, and test triage;
- `draft_patch` for candidate unified diffs that the frontier agent will review and apply;
- `generate_tests` for test code and edge-case tables;
- `review_diff` for non-security, non-final review;
- `map_change_impact` for symbol/reference impact analysis;
- `ask_local` only when none of the structured tools fits.

Delegate when the input is bounded, the output is advisory or reversible, verification is materially cheaper
than producing the result, and the task exercises no sensitive authority. For each substantial coding turn,
actively look for at least one eligible subtask. Do not delegate tiny tasks when tool latency and result review
would cost as much as doing the work directly. Read-only delegation may still return code, tests, or a unified
diff; it must not claim that those outputs were applied or verified.

Do not delegate credential handling, security or authorization decisions, destructive changes, production
operations, final correctness review, or tasks that require an unabridged large context. CastCLI MCP is
read-only, but local-model output remains untrusted until checked.

When delegation succeeds, use the returned answer instead of silently repeating the same work. Preserve the
compact CastCLI delegation receipt in progress reporting when useful. If the result is inadequate, explain
why before doing the task directly.

For verification, run cast-cli mcp-usage --fail-if-unused (or the equivalent Gradle command documented in
docs/CODEX_MCP.md). Use fresh, equivalent Codex sessions for A/B token comparisons.
