# CastCLI

**CastCLI is an LLM orchestration harness, not a media-casting tool.** It is a Java 21 CLI/library that
routes coding and agent tasks between local Ollama models and frontier cloud models (OpenAI, Anthropic,
Gemini), with dynamic tool selection, a cost-aware multi-agent commissioning pipeline, durable shared
memory, and Model Context Protocol (MCP) client/server support — a secure local AI control plane for
developer tools, casting each task to whichever model tier (local vs. cloud) fits it best.

# How it works

A provider-neutral Java 21 foundation for routing tasks among local LLMs and frontier cloud models. Features dynamic tool selection, sub-50ms deterministic fast-path execution, hardware presets for commercial GPUs (8GB, 12GB, 16GB, 24GB VRAM and Apple Silicon), a hierarchical multi-agent team pipeline with parallel execution and reviewer-driven rework, cost/token accounting, human-in-the-loop write/exec gating, checkpoint/resume, an optional semantic (embedding-based) workspace search index, and MCP client + server support.

LangChain4j supplies the chat and function-calling API; Jackson loads configuration; Picocli powers the CLI; and the JDK JShell API is available as an opt-in execution sandbox.

## Quick start

**No JDK, no build.** Download the bundle for your OS from the
[latest release](https://github.com/JustinANelson/CastCLI/releases/latest)
(`cast-cli-windows-x64.zip`, `cast-cli-linux-x64.zip`,
`cast-cli-macos-arm64.zip`, or `cast-cli-macos-x64.zip`) and unzip it — each bundle embeds its
own Java runtime. With
[Ollama](https://ollama.com) installed and running, `init` detects your hardware (VRAM on
Windows/Linux with an NVIDIA GPU, or Apple Silicon) and writes a matching starter config instead of
you hand-picking one of the `config/harness.vram-*.json` presets:

```powershell
./cast-cli.exe init
# follow the printed 'ollama pull ...' commands for any missing models, then:
./cast-cli.exe doctor
./cast-cli.exe ask "what time is it"
```

> **Cost cap included by default.** Every hardware preset ships with a spend ceiling in its
> `reliability` block (`maxCostUsdPerTask: 1.0`, `maxCumulativeCostUsd: 20.0`) so an enabled
> `FRONTIER_CLOUD` provider (e.g. OpenAI, Anthropic) can't run away with your API bill. `doctor`
> will warn you if a paid provider is enabled with no cap configured. Adjust these values in your
> config's `reliability` block to match your own budget.

Prefer a container? `docker run -i --rm ghcr.io/justinanelson/castcli:latest doctor` works
the same way without installing anything locally — see [docs/OPERATIONS.md](docs/OPERATIONS.md) for
volume-mount and config details.

### Building from source

```powershell
./gradlew.bat installDist
./build/install/cast-cli/bin/cast-cli.bat init
./build/install/cast-cli/bin/cast-cli.bat doctor
./build/install/cast-cli/bin/cast-cli.bat ask "what time is it"
```

No GPU, no Ollama yet, or want to inspect the config before running anything? `init` still works
(it falls back to the smallest preset, or accepts `--preset 8gb|12gb|16gb|24gb|apple-silicon`), or
copy a preset by hand:

```powershell
Copy-Item config/harness.example.json config/harness.local.json
./gradlew.bat test
./gradlew.bat run --args="models"
./gradlew.bat run --args="--config config/harness.example.json ask 'what time is it'"
./gradlew.bat run --args="--config config/harness.example.json commission 'Implement a thread-safe LRU Cache in Java'"
```

The configuration assumes Ollama's OpenAI-compatible API at `http://localhost:11434/v1/`. Change model names and enabled providers in `config/harness.local.json`. API keys are read from environment variables named in `apiKeyEnv` (e.g., `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`) -- never from config files. If exporting vars by hand is annoying while testing multiple providers, copy [`.env.example`](.env.example) to `.env` and fill it in; CastCLI reads it automatically as a fallback (a real environment variable of the same name always wins, and `.env` is gitignored).

## Durable Shared Memory

Enable the `memory` block to give CLI, MCP, and commissioned agents a shared, namespaced project memory.
The default backend is a local SQLite database at `.cast/memory/memory.db`. It uses WAL transactions and
optimistic versions so multiple CastCLI processes can safely read and write concurrently. Memories carry
scope, provenance, tags, importance, confidence, expiry, read-only status, and lineage; common credentials
and private keys are rejected before persistence.

Relevant memories are retrieved with hybrid keyword/vector scoring and injected into every model call within
`memory.maxContextChars`. Workspace-capable agents also receive tools to remember, update, recall, and forget
context. Developers can inspect the same store directly:

```powershell
./gradlew.bat run --args='memory remember architecture "Use SQLite WAL for shared memory" --tags database,memory'
./gradlew.bat run --args='memory recall "shared persistence"'
./gradlew.bat run --args='memory list --limit 20'
./gradlew.bat run --args='memory forget <id> --version <version>'
```

## Explainable Routing and Reliability

Routing now ranks every eligible provider using tier fit, tool capability, privacy/locality, configured token
cost, observed latency, and recent health. Privacy-marked prompts exclude cloud providers unless the caller
explicitly forces a cloud tier. Each decision retains its score and reasons for evaluation.

Provider execution is protected by a total request deadline, global concurrency limit, typed failure
classification, bounded exponential retry with jitter, explicit per-provider fallback order, and circuit
breaker cooldown. Operations with tools are never automatically retried because their side effects may not
be idempotent. Use `models --probe` for live `/models` readiness checks.

Routing policies can be evaluated without making model calls:

```powershell
./gradlew.bat run --args='route-eval evals/routing.example.json'
```

The versionable dataset records expected providers/tiers, cloud policy, and required tools. The report includes
case pass rate, provider/tier accuracy, privacy violations, cloud share, selected cost rate, and decision reasons.

## Observability and Reproducible Traces

Enable the observability block to emit vendor-neutral OpenTelemetry spans and metrics. Traces correlate the
request, ranked routing candidates, provider attempts/retries/fallbacks, model calls, tool/approval activity,
agent waves and subtasks, shared-memory retrieval/updates, and MCP requests. Token counts, operation latency,
estimated cost, failures, retries, approvals, tools, and memory operations are exported as metrics when OTLP
metrics are enabled.

Every enabled run is also archived as cross-process-safe JSONL (default .cast/traces/spans.jsonl) for local
debugging and run comparison. Prompts are represented by SHA-256 and length by default; set capturePrompts
only when trace storage is approved for raw prompt data.

    "observability": {
      "enabled": true,
      "serviceName": "cast-cli",
      "jsonlPath": ".cast/traces/spans.jsonl",
      "otlpEnabled": true,
      "otlpEndpoint": "http://localhost:4317",
      "otlpHeaders": {},
      "sampleProbability": 1.0,
      "capturePrompts": false,
      "metricsEnabled": true
    }

The OTLP endpoint works with an OpenTelemetry Collector or an OTLP-compatible backend such as Langfuse.
Put backend authentication headers in the local, gitignored config, not harness.example.json. ask prints
its trace ID for correlation, and telemetry state/export can be inspected without invoking a model:

    ./gradlew.bat run --args="telemetry"
    ./gradlew.bat run --args="telemetry --flush"

## Multi-Agent Hierarchical Pipeline

The harness includes an `AgentTeam` orchestrator that pairs high-capacity **Frontier Cloud models** (acting as Project Managers & Commissioning Agents) with **Local / Skilled Labor Agents**:

1. **PM Decomposition**: Frontier Cloud PM decomposes a goal into a `ProjectPlan` with structured subtasks assigned to worker roles (`CODER`, `TESTER`, `REVIEWER`, `GENERAL_LABOR`). This call is *strict*: it runs on `FRONTIER_CLOUD` or fails outright, never silently downgrading.
2. **Skilled Labor Execution**: Local models (or cheap tiers) execute subtasks using bound tools (`WorkspaceTools` including a gated write, `SystemTools`, `JavaShellTool`, `ProcessExecTool` for allow-listed build/test/VCS commands, plus any tools from configured MCP servers). Independent `CODER`/`GENERAL_LABOR` subtasks run concurrently; a `REVIEWER` rejection triggers one bounded rework pass of the wave it reviewed.
3. **PM Commissioning**: The PM reviews accumulated worker deliverables (context capped at `routing.maxContextChars`) and produces a final commissioning report, also strict on `FRONTIER_CLOUD`.

Progress checkpoints to `.cast/checkpoints/` after every wave, so `commission --resume <path>` can continue an interrupted run. `CommissioningResult` reports aggregate input/output tokens and estimated USD cost across the whole pipeline, plus an estimate of how much **FRONTIER_CLOUD spend was avoided** by routing worker subtasks to cheaper SMALL_LOCAL/LARGE_LOCAL tiers instead: the tokens those calls actually used, multiplied by a configured frontier provider's per-million-token rate, as a stand-in for "what this would have cost if a frontier model had done it." Both `ask` and `commission` print this alongside the regular token/cost summary whenever at least one enabled `FRONTIER_CLOUD` provider is configured.

`commission` also prints a **per-provider token breakdown** (`TokenUsageReport`): actual calls/tokens/cost for every provider that ran during the pipeline, plus a real cloud-vs-local comparison (total tokens that landed on FRONTIER_CLOUD vs. on SMALL_LOCAL/LARGE_LOCAL, the cloud's share of the total, and the local-minus-cloud token delta). This complements the frontier-savings estimate above: that one is a hypothetical ("what would this have cost on a frontier model"), this one is what actually happened, broken out by provider.

## Semantic Workspace Search

Large codebases outgrow `searchWorkspace`'s literal-text matching -- you need to find code by *meaning*, not just by keyword. Enable this with an `embeddings` block in your config:

```json
"embeddings": {
  "enabled": true,
  "baseUrl": "http://localhost:11434/v1/",
  "modelName": "nomic-embed-text",
  "chunkLines": 60,
  "chunkOverlapLines": 10
}
```

Then build the index and query it:

```powershell
./gradlew.bat run --args="index"
./gradlew.bat run --args='ask "where do we validate matchmaking tickets"'

# Or query the index directly, without depending on a model's tool-calling:
./gradlew.bat run --args='index --query "where do we validate matchmaking tickets" --max-results 5'
```

`cast-cli index` walks the workspace (respecting `embeddings.includeGlobs`/`excludeGlobs`, defaulting to common source extensions and excluding `build/`, `.git/`, `node_modules/`, etc.), chunks each file into overlapping line windows, embeds them with the configured embedding model (any OpenAI-compatible `/v1/embeddings` endpoint -- Ollama needs an embedding-capable model such as `nomic-embed-text` and to be started with embeddings support), and persists the result to `embeddings.indexPath` (default `.cast/index/workspace-embeddings.json`).

Reindexing is **incremental**: a file whose content hash hasn't changed since the last build is left untouched (no embedding-model call), and files deleted from disk have their chunks pruned -- so `index` is cheap to re-run after every edit rather than only on a schedule. Once built, `SemanticSearchTools.semanticSearchWorkspace` is automatically added alongside `WorkspaceTools` for any `ask`/`commission` task that already pulled in workspace tools, and `mcp-serve` also exposes it as `semantic_search_workspace` when embeddings are enabled. Disabled by default; nothing changes if you don't configure it.

Whether a local model actually *invokes* the tool (versus just describing what it would call) depends on that model/backend's function-calling support -- this is a general property of every tool the harness exposes, not specific to semantic search. `index --query` bypasses the model entirely and searches the index directly, which is useful both for verifying the index itself and for scripting searches outside an agent loop.

## Acting as an MCP Server or Client

MCP calls are audited by default to .cast/metrics/mcp-usage.jsonl. Successful local-model delegations include
a compact provider/model/token/cost/trace receipt. Run mcp-usage --fail-if-unused to prove delegation occurred,
or supply baseline and delegated Codex token counts for an A/B efficiency comparison. See
[docs/CODEX_MCP.md](docs/CODEX_MCP.md) for the Codex configuration and measurement workflow.

Run `cast-cli mcp-serve` to expose this harness as an MCP server over stdio. In addition to `ask_local`,
`list_models`, and workspace read/list/search tools, it publishes structured read-only delegations:
`summarize_files`, `analyze_failure`, `draft_patch`, `generate_tests`, `review_diff`, and
`map_change_impact`. Selected file context is assembled inside the server and oversized prompts are rejected
instead of silently truncated. Every `FRONTIER_CLOUD` provider is filtered from these calls.

The harness can also act as an MCP *client*: list stdio MCP servers under `mcpServers` in the config, and their tools become available to routed models alongside the harness's own Java tools.

### Attaching this project to Claude Code as an MCP server

1. Build the distribution once (re-run after any code change you want reflected):
   ```powershell
   ./gradlew.bat installDist
   ```
2. Register the built launcher as a stdio MCP server, pointing `--config` at your own local config:
   ```powershell
   claude mcp add cast-cli -- `
     "C:\path\to\CastCLI\build\install\cast-cli\bin\cast-cli.bat" `
     --config "C:\path\to\CastCLI\config\harness.local.json" mcp-serve
   ```
   (On macOS/Linux, use the `bin/cast-cli` shell script instead of the `.bat`.)
3. Confirm it's attached:
   ```powershell
   claude mcp list
   ```
   You should see `cast-cli: ... - ✔ Connected`. Inside a Claude Code session, the exposed tools appear as `mcp__cast-cli__ask_local`, `mcp__cast-cli__list_models`, `mcp__cast-cli__list_workspace_files`, `mcp__cast-cli__read_workspace_file`, `mcp__cast-cli__search_workspace`, and (when `embeddings.enabled` is `true` and `cast-cli index` has been run at least once) `mcp__cast-cli__semantic_search_workspace`.
   The server also exposes `summarize_files`, `analyze_failure`, `draft_patch`, `generate_tests`,
   `review_diff`, and `map_change_impact`; use these structured tools before the generic
   `ask_local` entry point when one matches the task.
4. Make sure Ollama (or whatever `baseUrl` your config points at) is running, and that the model names in your config match `ollama list` output, or `ask_local`/`list_models` will report the provider as not-ready.

Because `mcp-serve` always strips `FRONTIER_CLOUD` providers before building its tool config (see [Safety](#safety)), this is a one-way delegation: Claude Code can push cheap, low-stakes work down to your local models, but nothing routed through this server can turn around and spend your frontier API budget.

## Human-in-the-Loop Approval

Write and process-exec tools are double-gated: `tools.allowWrites`/`tools.allowShellExec` must be `true` in config, and each call must also pass an `ApprovalGate`. The CLI defaults to `ConsoleApprovalGate` (interactive `y/N` prompt); pass `--yes` to auto-approve for unattended runs.

## Hardware Presets

Hardware-tuned configurations are included in `config/` for standard commercial GPUs and Apple Silicon:

- **8GB VRAM (`config/harness.vram-8gb.json`)**: (RTX 3060 8GB, RTX 4060, RX 6600/7600)
  - `qwen2.5-coder:7b-instruct-q4_K_M` (~4.5 GB) & `deepseek-r1:8b` (~5 GB).
- **12GB VRAM (`config/harness.vram-12gb.json`)**: (RX 6700 XT / 7700 XT, RTX 3060 12GB, RTX 4070)
  - `qwen2.5-coder:7b` (~8 GB) & `qwen2.5-coder:14b-instruct-q4_K_M` (~9 GB).
- **16GB VRAM (`config/harness.vram-16gb.json`)**: (RTX 4060 Ti 16GB, RTX 4070 Ti Super, RX 6800 / 7800 XT)
  - `qwen2.5-coder:7b-instruct-q8_0` (~8 GB) & `qwen2.5-coder:14b-instruct-q8_0` (~15 GB).
- **24GB VRAM (`config/harness.vram-24gb.json`)**: (RTX 3090, RTX 4090, RX 7900 XTX 24GB)
  - `qwen2.5-coder:14b` (~14 GB) & `qwen2.5-coder:32b-instruct-q4_K_M` (~19 GB).
- **Apple Silicon (`config/harness.apple-silicon.json`)**: (Mac M1/M2/M3/M4 Pro/Max/Ultra with Unified Memory)
  - `qwen2.5-coder:14b` & `qwen2.5-coder:32b` with Metal hardware acceleration.

## Using as a Java Library in Other Projects

Add `CastCLI` to your build, and instantiate `AgentTeam` or `HarnessOrchestrator`:

```java
HarnessConfig config = new ConfigLoader().load(Path.of("config/harness.vram-12gb.json"));

// Direct task execution with dynamic tool routing and fast-path
HarnessOrchestrator orchestrator = new HarnessOrchestrator(config);
Outcome outcome = orchestrator.run(new TaskRequest("list workspace files matching *.java", Workload.AUTO, null));

// Hierarchical multi-agent team execution
AgentTeam team = new AgentTeam(config);
CommissioningResult result = team.commission("Implement a rate limiter service");
System.out.println(result.commissioningSummary());
System.out.printf("%d in / %d out tokens, est. $%.4f%n",
        result.totalInputTokens(), result.totalOutputTokens(), result.estimatedCostUsd());

// Resume a prior run from its checkpoint instead of re-planning
CommissioningResult resumed = team.commission("Implement a rate limiter service", result.checkpointPath());
```

See [docs/COMMANDS.md](docs/COMMANDS.md) for CLI options and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for extension points.

## Safety

Workspace tools normalize every path and reject paths outside `tools.workspaceRoot`. JShell can execute arbitrary JVM code and therefore starts disabled. Enable it only when every prompt and model endpoint involved is trusted; it is not an operating-system sandbox. Writes and process execution are off by default (`tools.allowWrites`/`tools.allowShellExec`) and additionally require an `ApprovalGate` to approve each call; `ProcessExecTool` only runs a fixed allow-list of commands, never an arbitrary shell string. `mcp-serve` always strips `FRONTIER_CLOUD` providers from the config it builds tools against, so it cannot be used to proxy calls to a frontier model.
