# Architecture

The CLI turns input into a `TaskRequest`. `ModelRouter` combines an explicit tier, a workload hint, and small deterministic heuristics to choose an enabled provider. `ChatModelFactory` converts the selected provider into LangChain4j's common `ChatModel` API. `HarnessOrchestrator` then invokes the model directly or creates an AI Service with tools when that provider supports function calling.

```text
CLI -> TaskRequest -> ModelRouter -> ProviderConfig -> ChatModelFactory
                                                   -> LangChain4j ChatModel
                                                   -> tools -> final answer
```

OpenTelemetry context follows this flow, including virtual-thread retry attempts and parallel agent waves.
CastTelemetry is the small internal facade; it keeps instrumentation independent of a specific backend,
exports OTLP spans/metrics when configured, and always supports an optional local JSONL span archive for
reproducibility. The root castcli.request trace ID is returned on HarnessOrchestrator.Outcome.

## Provider strategy

- `SMALL_LOCAL`: low-latency classification, formatting, summaries, and simple transformations.
- `LARGE_LOCAL`: code, repository work, longer context, and private-data-heavy tasks.
- `FRONTIER_CLOUD`: the hardest reasoning tasks or explicit requests when policy allows cloud use.

Routing is deliberately separate from provider construction, making policy easy to test and replace. If the exact requested tier is unavailable, the current router selects the nearest enabled tier. Cloud profiles whose configured key environment variable is missing are unavailable automatically.

All endpoints currently use the broadly supported OpenAI-compatible protocol. Add a provider-specific LangChain4j module and branch in `ChatModelFactory` when a native integration provides capabilities you need.

The inbound gateway applies admission control before authentication/dispatch and independently bounds request
bytes, JSON depth/string size, message/tool counts, total in-flight requests, and streaming responses. Overload
returns `503` with `Retry-After`; its public metrics snapshot exposes accepted, rejected, oversized, queued, active,
and byte totals. Both HTTP servers own and drain their virtual-thread executors during shutdown.

## Tool strategy

Tool-capable providers receive Java tool groups selected by `DefaultToolSelector` from the prompt/workload:

- `WorkspaceTools`: bounded read, glob listing, literal search, and (when `tools.allowWrites=true`) a
  gated `writeWorkspaceFile`, all confined to a configured root.
- `SystemTools`: current time with an explicit IANA zone.
- `JavaShellTool`: JShell evaluation, disabled by default.
- `ProcessExecTool`: runs a fixed allow-list of build/test/VCS commands (`gradlew test`, `git diff`, ...)
  as direct processes (no shell interpretation), gated by `tools.allowShellExec`.

Every write or process-exec call also passes through an `ApprovalGate` (`AutoApprovalGate` for
unattended/library use, `ConsoleApprovalGate` for interactive CLI use) before it runs, independent of the
`tools.allowWrites`/`allowShellExec` config gate — both must allow the call. LangChain4j's AI Services loop
publishes the annotated methods as model tools, executes requested calls, returns tool results to the
model, and records tool names in the CLI output. Keep write, process, network, email, and deployment tools
in separate permission-scoped classes. Do not expose a powerful tool merely because a provider supports
function calling.

External tools can also be pulled in dynamically: `McpClientManager` launches the stdio servers listed in
`mcpServers` and exposes their tools to the routed model via a LangChain4j `ToolProvider`, alongside the
harness's own Java tools.

`SemanticSearchTools` is added on top of `WorkspaceTools` whenever `embeddings.enabled=true` in config *and*
`DefaultToolSelector` already selected `WorkspaceTools` for the task (this check lives in
`HarnessOrchestrator.run`, not in `DefaultToolSelector`, since selection only needs `ToolConfig` while the
embedding index needs the full `EmbeddingConfig`). It's built once per `HarnessOrchestrator` instance and
reused across calls, since constructing the embedding client and index wrapper is comparatively expensive.
`McpStdioServer` registers the equivalent `semantic_search_workspace` tool the same way, directly from the
top-level config (not the cheap-tiers-only config it builds for `ask_local`) so the capability isn't lost
when frontier providers are filtered out.

## Semantic workspace index

`WorkspaceEmbeddingIndex` (`dev.justnels.castcli.index`) builds and queries a persisted
`InMemoryEmbeddingStore<TextSegment>` (from LangChain4j core) as a semantic complement to
`WorkspaceTools.searchWorkspace`'s literal matching. `EmbeddingModelFactory` builds the embedding client the
same way `ChatModelFactory` builds chat clients: any OpenAI-compatible `/v1/embeddings` endpoint, configured
via `EmbeddingConfig` (baseUrl/modelName/apiKeyEnv), so a local Ollama embedding model and a hosted provider
are both just config.

`rebuild()` (invoked by `cast-cli index`) walks the workspace under `embeddings.includeGlobs`/`excludeGlobs`,
splits each file into overlapping line-count windows (`chunkLines`/`chunkOverlapLines` — a fixed-size line
window rather than LangChain4j's paragraph-based `DocumentSplitters`, so every chunk carries an exact
`startLine`/`endLine` for `file:line`-style results), and stores each chunk's `source` path and a SHA-256
`contentHash` as segment metadata. Rebuilds are incremental purely via `EmbeddingStore`'s own API — no
separate manifest file:

- A file whose hash matches what's already indexed is left alone. Existing chunks for a path are fetched
  with a **zero-vector, filter-only, `minScore=0` search** (`scanWithFilter`): since every entry passes when
  `minScore` is 0, this returns every chunk matching the metadata filter regardless of embedding content —
  an "enumerate by metadata" query built entirely out of `EmbeddingStore.search`, with no new API needed.
- A changed or new file has its old chunks removed via `store.removeAll(filter)` and is re-embedded.
- Files present in the store but no longer on disk (by comparing the pre-rebuild distinct `source` values,
  gathered the same zero-vector-scan way with no filter, against the current file list) are pruned the
  same way.

Only added/changed files ever reach the embedding model, so re-running `index` after a small edit is cheap
regardless of workspace size. `search()` embeds the query text and does a real similarity search over the
persisted store, returning `SearchHit`s with source path, line range, and score. `SemanticSearchTools` wraps
`search()` as a model tool; both it and `cast-cli index` fail loudly (`IllegalStateException`) if no
index has been built yet, rather than silently returning nothing.

## Cost, tokens, and tier enforcement

`HarnessOrchestrator.Outcome` carries input/output token counts and an estimated USD cost derived from
each `ProviderConfig`'s `costPerMillionInputTokens`/`costPerMillionOutputTokens`. `AgentTeam` aggregates
these across every PM, worker, and report call into `CommissioningResult`.

`CostSavingsEstimator` estimates how many FRONTIER_CLOUD tokens/dollars were avoided by routing calls to
SMALL_LOCAL/LARGE_LOCAL tiers instead: it picks the first enabled FRONTIER_CLOUD provider in the config as
a reference rate, and for every non-frontier call, computes what that call's actual input/output tokens
would have cost at the reference provider's per-million rate. `AgentTeam` folds this into
`CommissioningResult.tokensOffloadedToLocal`/`estimatedFrontierCostAvoidedUsd`, and the CLI's `ask` and
`commission` commands print it alongside the regular token/cost summary. With no enabled FRONTIER_CLOUD
provider configured, every estimate is zero rather than failing.

`TokenUsageReport` is the actual-usage counterpart: it compiles real per-provider token/call/cost totals
(no hypothetical rate substitution) and compares the sum of everything that landed on a FRONTIER_CLOUD
provider against the sum that landed on SMALL_LOCAL/LARGE_LOCAL providers (`Summary.cloudTokens()` /
`localTokens()` / `cloudShare()` / `localMinusCloudTokens()`). `record()` is backed by a
`ConcurrentHashMap` of per-provider atomic accumulators, so `AgentTeam` can call it from parallel worker
waves without external synchronization; `summarize()` produces the immutable, provider-id-sorted
`Summary` returned as `CommissioningResult.tokenUsageByProvider()` and printed by `commission`. A provider
that never ran doesn't appear in the breakdown at all, rather than showing up as a zeroed-out row.

`TaskRequest.strict()` makes tier or provider selection load-bearing rather than advisory:
`ModelRouter` requires an exact match and `HarnessOrchestrator` does not fall back on failure.
`CommissioningConfig` can assign an exact provider ID to the project manager and each worker role.
Assigned calls are strict; null assignments preserve policy routing. This supports both cloud-PM/local-worker
teams and fully local hierarchies without adding model-size assumptions to the tier enum.

## Shared memory

`MemoryStore` is the durable-memory contract. `SqliteMemoryStore` is the default cross-process backend and
uses SQLite WAL, immediate write transactions, a busy timeout, SHA-256 deduplication, and optimistic record
versions. `InMemoryMemoryStore` provides the same contract for tests and embedded use. Records are namespaced
and include scope, tags, provenance, importance, confidence, expiry, read-only status, and lineage.

Search combines lexical overlap with a deterministic local vector index and metadata weights. Before a model
call, `MemoryContextProvider` retrieves relevant project memories and appends them as bounded, untrusted context.
`SessionMemorySummarizer` tracks `SessionAction` streams and invokes the local LLM (`SMALL_LOCAL` tier) in the background to asynchronously produce structured session action summaries and save them to the long-term memory store under namespace `session` and scope `session-turnover`, facilitating multi-agent and multi-session context handoffs.
The MCP and Java tool paths share the same database. Secret-pattern rejection, expiry/retention cleanup,
read-only records, and version checks prevent the common accidental-leak and lost-update failure modes.

## Routing evaluation and reliability

`RoutingStrategy` is pluggable; `PolicyRoutingStrategy` ranks capability-compatible providers using tier fit,
privacy/locality, cost, live latency, and recent health, returning explainable `RoutingCandidate` values.
`RoutingEvaluator` replays JSON datasets without model calls and reports accuracy, privacy violations, cloud
share, cost rate, selected tools, and decision reasons.

`ReliabilityExecutor` owns retries so provider SDK retries cannot multiply them. It enforces the total deadline,
virtual-thread cancellation, a fair concurrency semaphore, classified retryability, jittered exponential
backoff, circuit cooldown, and observed latency. Tool-bearing executions receive one attempt. Explicit fallback
edges in `reliability.fallbackOrder` run before remaining ranked candidates, while strict tasks never fall back.

## Multi-agent execution model

`AgentTeam` groups a PM-generated plan into waves: consecutive `CODER`/`GENERAL_LABOR` subtasks run
concurrently on virtual threads (they're assumed independent), while `REVIEWER`/`TESTER` subtasks run
alone after the wave they depend on completes. A `REVIEWER` subtask that ends its output with
`VERDICT: REJECTED: ...` triggers one bounded rework pass (`routing.maxReworkIterations`) of the wave it
reviewed, with the rejection feedback appended to the reworked prompts, followed by a re-review. Context
handed to later workers is capped at `routing.maxContextChars`: the most recent subtask outputs are kept
in full, older ones collapse to a one-line summary, so the prompt sent to a small local model cannot grow
without bound across a long plan. Progress is checkpointed atomically to `.cast/checkpoints/<goal-hash>.json`
after every wave; `AgentTeam.commission(goal, checkpointPath)` resumes from there, skipping already
completed subtasks. Checkpoint and provider-health JSON writes are flushed to same-directory temporary files,
retain a `.bak` previous version, and recover from that backup when the primary cannot be decoded.

## Acting as an MCP server

`McpStdioServer` implements the current MCP 2025-11-25 stdio transport directly (newline-delimited
JSON-RPC 2.0), negotiates supported older revisions during initialization, and exposes `ask_local`,
`list_models`, and the workspace read/list/search tools. It is constructed from a `HarnessConfig` with every `FRONTIER_CLOUD`
provider filtered out, so it can never proxy a call to a frontier model — the point is to give a frontier
agent (e.g. Claude Code, wired up as an MCP client) a place to offload routine, low-stakes subtasks onto
this harness's cheap local/small tiers. Run it with `cast-cli mcp-serve`.

Deterministic read-only delegation results use a versioned-key LRU bounded by both entry count and UTF-8 bytes.
MCP response metadata reports cache entries, bytes, hits/misses, evictions, and key-schema version so operators
can validate that caching saves work without allowing unbounded growth.

The MCP delegation compiler budgets context before dispatch: it caps literal-search lines, partitions large
files/logs/diffs into at most four prompt packets, reserves output headroom, and performs a bounded reduce pass.
MCP-local reliability uses one strict provider attempt within a total deadline, configured by
`reliability.mcpDelegationDeadlineSeconds` (default 60s). Multi-partition calls (`summarize_files`,
`generate_tests`, etc.) split this deadline evenly across each partition round-trip plus the final reduce
pass, so one slow partition can't starve the rest of their share. Raise this value for larger repos or
slower/larger local models where the 60s default causes consistent timeouts and fallbacks (visible as
`timeouts` and `fallbacks` counts per tool in `mcp-usage`). Failed request hashes are retained briefly to
suppress unchanged retries and signal direct frontier fallback. Usage summaries expose per-tool p50/p95
latency, timeouts, context rejections, and fallback counts.

## Extension points

1. Add provider metadata or capabilities to `ProviderConfig`.
2. Replace or enrich `ModelRouter` with latency, health, and privacy signals (cost and strict-tier
   enforcement already exist).
3. Add a narrowly scoped `@Tool` class and register it in `DefaultToolSelector`.
4. Add chat memory, retrieval, and tracing behind the orchestration layer.
5. Keep CLI behavior thin so Antigravity IDE, Claude Code, Codex CLI, services, and tests can invoke the same Java classes.
