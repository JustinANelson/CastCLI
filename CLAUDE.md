# CastCLI

This project *is* CastCLI: a Java 21 harness that routes tasks across local Ollama models and
frontier cloud models, with tool selection, a multi-agent commissioning pipeline, and MCP client/server
support. See [README.md](README.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and
[docs/COMMANDS.md](docs/COMMANDS.md) for the full picture.

## Use the harness's own MCP tools while working in this repo

This repo is registered as a local-scope MCP server (`claude mcp add`, pointing at
`build/install/cast-cli/bin/cast-cli.bat --config config/harness.local.json mcp-serve`),
so `mcp__cast-cli__*` tools are available in every Claude Code session opened here. Actually reach for them:

- **`ask_local`**: delegate routine, low-stakes subtasks (formatting, summaries, boilerplate, simple
  lookups) to the configured local models instead of spending frontier tokens on them. Never proxies to
  FRONTIER_CLOUD by design.
- **Structured delegation**: prefer `summarize_files`, `analyze_failure`, `draft_patch`,
  `generate_tests`, `review_diff`, or `map_change_impact` when one matches. These tools assemble
  bounded context and make the intended verification boundary explicit while remaining read-only.
- **Delegation threshold**: hand off advisory or reversible work whose result is cheaper to verify than
  recreate. Avoid latency-dominated microtasks. Local patch and test output is a draft; review, apply, and
  verify it with the normal repository workflow.
- **Workloads**: `ask_local.workload` accepts only `AUTO`, `QUICK`, `CODE`, or `REASONING`;
  invalid values fail instead of silently falling back.
- **`semantic_search_workspace`**: find code by meaning, not just literal text -- prefer this over
  `search_workspace`/grep when the query is conceptual ("where do we handle X") rather than an exact
  string. Requires the index to exist; if it errors, run `./gradlew.bat run --args="index"` first (see
  below), or fall back to `search_workspace`/`Grep`.
- **`list_models`** / `list_workspace_files` / `read_workspace_file` / `search_workspace`: use these over
  ad hoc shell commands when just inspecting this workspace or the harness's own model config.

If `claude mcp list` shows `cast-cli` disconnected or failing, the built binary is likely stale or a
previous server process is holding its jars locked (Windows). Fix: stop any stale
`cast-cli` java process, then `rm -rf build/install && ./gradlew.bat installDist`.

## Keeping the harness itself working

- **After changing harness source** (anything under `src/main`): re-run `./gradlew.bat installDist`. The
  MCP server and the `mcp` registration run the *compiled* binary, not source -- edits don't take effect
  until reinstalled.
- **After editing files you want semantically searchable**: re-run `./gradlew.bat run --args="index"`.
  It's incremental (unchanged files are skipped by content hash), so this is cheap to run often.
- **Keep `config/harness.local.json` model names in sync with `ollama list`.** A misconfigured model name
  doesn't fail loudly -- non-strict routing silently falls back to another tier, which can look like a
  routing bug when it's actually just a typo'd tag.
- Run `./gradlew.bat test` before considering harness-source changes done; `./gradlew.bat build` also
  builds the distribution.

