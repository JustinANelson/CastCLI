# CastCLI Product Roadmap

This roadmap describes the planned evolution of CastCLI after version 0.1.1. It is an implementation
plan, not a promise of release dates. Priorities may change as routing evaluations, user feedback,
security reviews, and provider APIs evolve.

## Product direction

CastCLI will not compete primarily on the number of model providers it proxies. Provider breadth,
generic retries, fallbacks, budgets, caching, dashboards, and virtual keys are becoming standard AI
gateway capabilities. CastCLI's differentiated category is:

> **The verifiable, local-first execution firewall for coding agents.**

The product promise is:

- Keep source code and sensitive context local whenever possible.
- Minimize and disclose exactly what crosses a cloud boundary.
- Escalate only when local work fails measurable checks or exceeds a declared capability.
- Attach evidence to routing, delegation, approval, and validation decisions.
- Demonstrate cost and token savings without quietly sacrificing quality.

CastCLI already has the foundation for this direction: explainable routing, MCP delegation, local/cloud
tier isolation, approval-gated tools, reliability controls, shared memory, semantic workspace search,
OpenTelemetry traces, checkpoints, routing evaluation, and cost accounting.

## Planning principles

1. **Evidence before autonomy.** A model output is not successful merely because the model returned it.
   Prefer compilation, tests, schema validation, patch applicability, citations, or independent review.
2. **Local first, not local only.** Frontier models remain useful, but cloud escalation must be explicit,
   minimal, policy-compliant, and measurable.
3. **Policy is a hard boundary.** Privacy, authority, budget, and tool restrictions must not be prompt-only
   conventions.
4. **Adopt existing agents.** CastCLI should improve Codex, Claude Code, Cursor, Continue, Aider, and other
   clients rather than require users to replace them.
5. **Expose reasons.** Every consequential routing, filtering, fallback, and approval decision should be
   inspectable and reproducible.
6. **Optimize outcomes, not calls.** Cost, latency, and quality are measured at the task or goal level, not
   only for individual model requests.
7. **Secure defaults remain non-negotiable.** New integrations and convenience features must preserve
   workspace confinement, least privilege, secret isolation, and default-deny execution.

## Status and priority

- **P0:** Required to establish the product's adoption surface or core differentiation.
- **P1:** High-value capability that deepens the product advantage.
- **P2:** Expansion, ecosystem, team, or operational maturity work.
- **Proposed:** Accepted roadmap work that has not started.
- **In progress:** An implementation is actively being developed.
- **Complete:** Acceptance criteria are met and documentation/tests have shipped.

All items below are **Proposed** unless their status is updated in a future change.

## Release plan

### 0.2 — Compatibility and adoption

Goal: make CastCLI usable as a transparent control plane for existing coding agents and SDK clients.

#### R-001: OpenAI-compatible inbound gateway — P0

**Status: In progress.** Phase 1 (non-streaming `/v1/chat/completions` and `/v1/models`, no client
tool passthrough, loopback-default with fail-closed bearer auth) and Phase 2 (SSE streaming with
cooperative, poll-based client-disconnect cancellation and optional `stream_options.include_usage`)
are done. Client-side tool passthrough and multi-turn history remain as follow-on phases before this
item can move to Complete. Known gaps carried forward: guardrail filtering on the streaming path is
per-chunk only (a redacted pattern split across two streamed tokens is not caught); client-disconnect
detection relies on the next attempted write failing, so a stalled/paused generation is not noticed
until the next token attempt.

Add a locally hosted API surface so clients can adopt CastCLI by changing a base URL.

Scope:

- Implement `/v1/chat/completions` and `/v1/responses` compatibility.
- Support non-streaming and streaming text responses.
- Support tool definitions, tool calls, and streamed tool-call arguments.
- Preserve request IDs, usage information, errors, cancellation, and trace correlation.
- Map routing, budget, reliability, privacy, and approval failures to stable API errors.
- Bind to loopback by default; require explicit configuration and authentication for non-loopback use.
- Document Codex, Claude Code, Cursor, Continue, Aider, and generic OpenAI SDK configuration.

Acceptance criteria:

- A supported client can use CastCLI by changing only endpoint/authentication configuration.
- Streaming tool calls pass integration tests and do not bypass approval gates.
- Disconnecting a client cancels downstream work within a bounded interval.
- The gateway passes protocol fixtures for supported request and response fields.
- Network exposure and authentication behavior have dedicated security tests.

#### R-002: Provider capability registry and discovery — P0

Replace coarse assumptions such as `toolsEnabled` and `maxToolsSupported` with an explicit capability
model.

Scope:

- Record context window, output limit, tokenizer, modalities, structured output, streaming, tool use,
  parallel tools, reasoning controls, embeddings, and supported endpoint families.
- Discover capabilities when a provider exposes reliable metadata; otherwise use versioned catalog data
  with operator overrides.
- Probe capabilities safely without billable generation where possible.
- Make the router reject incompatible providers with a human-readable reason.
- Surface stale, unknown, or contradicted capability data through `doctor` and `models`.

Acceptance criteria:

- Routing tests cover every declared capability as a hard eligibility constraint.
- Unknown capabilities fail safely for features that require them.
- `models` explains the source and age of capability data.

#### R-003: Native provider adapters — P0

Preserve provider-specific behavior instead of forcing every provider through the lowest common
OpenAI-compatible denominator.

Scope:

- Introduce an internal provider adapter interface independent of routing policy.
- Add native OpenAI Responses, Anthropic Messages, and Gemini generation adapters.
- Preserve provider-native tool use, streaming, usage, caching controls, reasoning options, and errors.
- Retain the OpenAI-compatible adapter for Ollama, LM Studio, llama.cpp, vLLM, and custom hosts.
- Add adapter contract tests using mock servers; never require paid credentials in the default suite.

Acceptance criteria:

- Every adapter retains CastCLI trace, token, and cost attribution.
- Provider failures map to typed CastCLI failures without discarding useful diagnostic metadata.
- Native support remains a capability enhancement, not a cloud requirement.

#### R-004: Integration generator — P1

Add `cast connect <client>` for `codex`, `claude`, `cursor`, `continue`, `aider`, and generic SDK use.

- Detect existing configuration and show a diff before writing.
- Provide `--check` to verify connectivity without modifying files.
- Never overwrite credentials or unrelated configuration.
- Provide a reversible disconnect/restoration workflow for changes CastCLI made.
- Maintain tested fixtures and a documented manual fallback for every supported client.

#### R-005: Dry-run and decision explanation — P0

- Add `cast dry-run` to show candidates, context sources, tools, approvals, privacy classification,
  expected cloud egress, and estimated cost without invoking a model.
- Add `cast explain <trace-id>` for a complete routing and execution explanation.
- Include exclusion reasons for providers and tools, not only the winning candidate.
- Provide stable JSON output for automation and policy testing.
- Guarantee that dry-run performs no generation, writes, tool execution, or external egress probes.

#### R-006: Configuration schema and migration framework — P1

- Publish a versioned JSON Schema with editor completion and validation.
- Add `cast config migrate`, automatic backups, idempotent migrations, and deprecation warnings.
- Distinguish syntax, security, readiness, and migration errors.
- Validate every checked-in example and maintain migration fixtures for prior versions.
- Leave the original configuration untouched after a failed migration.

#### 0.2 release gate

- Existing CLI and MCP behavior remains backward compatible or has a documented migration.
- At least one real client completes streaming text and tool-call end-to-end tests through the gateway.
- Gateway, adapters, and configuration changes pass threat-model review.
- `doctor`, command documentation, sample configurations, and operational documentation are updated.

### 0.3 — Verifiable local-first execution

Goal: make local-first routing an evidence-based workflow rather than a one-time model selection.

#### R-101: Outcome-aware escalation engine — P0

Change routing from a single pre-call selection into a bounded execution cascade:

```text
local attempt -> deterministic validation -> targeted retry -> cloud escalation -> final validation
```

Scope:

- Define validation contracts for compilation, tests, linting, schemas, patch application, citations,
  structured output, and reviewer agreement.
- Represent pass, fail, indeterminate, and policy-blocked outcomes.
- Retry or escalate only the failed or uncertain portion when it can be isolated.
- Enforce task-level deadlines, request limits, token limits, and dollar limits across the cascade.
- Prevent validators from acquiring greater tool or data authority than the original task.
- Record why local work was accepted, retried, rejected, or escalated.

Initial workflows:

- Local test generation followed by compilation and test discovery.
- Local patch drafting in an isolated worktree followed by apply, build, and test checks.
- Local summaries verified against real file/line citations.
- Two-local-model agreement with escalation on material disagreement.
- Structured extraction verified against a declared schema.

Acceptance criteria:

- A successful validator can prevent an unnecessary cloud call.
- A failed validator cannot be represented as a successful task.
- Budget exhaustion stops deterministically and preserves evidence.
- Evaluation reports task success, escalation rate, cost, and latency together.

#### R-102: Context Firewall and cloud egress manifests — P0

Create a hard policy boundary between local context and cloud providers.

Scope:

- Classify context as `PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, or `RESTRICTED`, with configurable labels.
- Minimize source before cloud dispatch using symbol extraction, interfaces, summaries, and redaction.
- Enforce deny globs, classification, source line/byte/token limits, provider region, declared retention,
  and approval requirements.
- Produce an egress manifest with hashes, classifications, transformations, destination, purpose, and
  approval decision.
- Show the manifest before dispatch when interactive approval is required.
- Fail closed when required provider privacy metadata is unavailable.

Example policy:

```yaml
policy:
  cloud:
    allowedClassifications: [PUBLIC, INTERNAL]
    maxSourceLinesPerRequest: 200
    denyGlobs: ["**/customer/**", "**/*.pem"]
    requireApprovalFor: [RAW_SOURCE, MEMORY]
    allowedRegions: ["us-east"]
    retention: zero
```

Acceptance criteria:

- Cloud adapters cannot receive context until the firewall returns an allow decision.
- Tests prove denied content never appears in outbound requests or telemetry.
- Every cloud trace references an egress manifest, including user-forced cloud requests.

#### R-103: Repository-aware token compiler — P0

- Add AST- and symbol-aware slicing for supported languages.
- Add call-graph/change-impact expansion and test-to-production-code mapping.
- Prefer Git-diff-first context selection.
- Deduplicate repeated file fragments and previously supplied context.
- Compress logs, stack traces, and test failures while preserving actionable evidence.
- Filter generated, vendored, binary, lockfile, and build-output content.
- Use progressive disclosure from summary, to symbol, to exact lines.
- Count tokens with the target provider's tokenizer before dispatch.
- Add content-addressed caching keyed by repository state, policy, model, and transformer version.
- Require source provenance and stable line/symbol metadata for every emitted fragment.
- Benchmark token reduction and task quality against the current context builder.

#### R-104: Proof-carrying delegation receipts — P0

Expand MCP receipts into reproducible, optionally signed execution records containing:

- Request, selected-context, output, and patch hashes.
- Policy, configuration, router, prompt-template, and validator versions.
- Model, provider, runtime, quantization, and capability identity where available.
- Ranked candidates, exclusions, fallback decisions, and escalation reasons.
- Files, memory records, MCP servers, and tools accessed.
- Cloud egress manifest references.
- Token, latency, estimated cost, and optional energy totals.
- Validation commands/results, approvals, and checkpoint lineage.

Commands:

- `cast explain <trace-id>`
- `cast replay <trace-id>`
- `cast verify <receipt>`
- `cast compare <trace-a> <trace-b>`

Acceptance criteria:

- Verification detects modification of covered artifacts.
- Replay distinguishes reproduced, substituted, unavailable, and nondeterministic components.
- Signing is optional and key material never enters a prompt or trace.

#### R-105: Disposable worktree/container execution — P0

- Run patching, writes, builds, and tests in a disposable Git worktree by default when Git is available.
- Offer an optional container sandbox with explicit filesystem, process, resource, and network policy.
- Present a reviewed diff and validation result before promotion to the primary workspace.
- Preserve failed sandboxes for inspection subject to retention policy.
- A rejected or failed run must not modify the primary worktree.
- Promotion must detect conflicting user changes and never overwrite them silently.
- Never market JShell alone as an operating-system sandbox.

#### R-106: Shadow routing and champion/challenger comparison — P0

- Record what shadow policies would select without affecting actual routing.
- Estimate or measure cost, latency, privacy, escalation, and validation outcomes.
- Support replay against routing datasets and archived traces.
- Produce champion/challenger reports and confidence intervals when sample size permits.
- Never issue shadow model calls unless separately enabled and budgeted.
- Clearly distinguish predicted results from measured calls.

#### R-107: Policy-as-code profiles — P1

- Add `offline`, `private`, `balanced`, `fast`, `cheap`, `frontier`, and `regulated` profiles.
- Cover providers, classification, tools, validators, budgets, fallbacks, and approvals.
- Allow repository policy plus stricter user/organization policy; a lower scope cannot weaken a higher one.
- Add `cast policy test` with fixture-based allow/deny/escalation assertions.
- Include resolved-policy explanations in dry-run and receipts.

#### 0.3 release gate

- At least three bounded coding workflows demonstrate validated local success and selective escalation.
- Cloud-bound requests always pass the Context Firewall and produce an egress manifest.
- Worktree isolation and promotion receive adversarial, dirty-worktree, and cross-platform tests.
- A published evaluation compares quality, cost, tokens, latency, and privacy violations with 0.2.

### 0.4 — Adaptive routing and measurable quality

Goal: tune routing to the user's hardware, models, repositories, and actual task outcomes.

#### R-201: Local model capability profiler — P0

Add `cast benchmark` to measure:

- Code-summary fidelity and citation accuracy.
- Patch applicability, compilation, and test pass rate.
- Test quality and mutation-killing ability where practical.
- Tool-call reliability and argument correctness.
- JSON/schema adherence and instruction following.
- Performance by language, repository, and task category.
- Tokens per second, latency, context limits, VRAM/RAM use, and optional energy use.

Ship a small safe suite and support repository-derived private suites. Store results by model digest,
quantization, runtime, hardware, and benchmark version; invalidate results after material changes. `models`
must distinguish declared, probed, and benchmarked capabilities, and routing decisions should cite relevant
benchmark evidence. Benchmarks remain offline unless cloud use is explicitly requested.

#### R-202: Repository-specific calibration and feedback — P0

- Learn task-class thresholds from validator outcomes, explicit feedback, latency, and cost.
- Support opt-in, repository-local calibration data.
- Protect against feedback poisoning, sparse samples, and sudden drift.
- Retain a deterministic heuristic fallback.
- Version learned policies and support instant rollback.
- Require an adaptive policy to meet declared quality SLOs against the heuristic baseline before activation.
- Keep raw repository content local and explain which evidence influenced a decision.

#### R-203: Quality, cost, privacy, and latency SLOs — P0

Allow requests such as:

- “At least 95% schema-valid, under $0.05, no cloud source code.”
- “Tests must pass; use cloud only after two local failures.”
- “Interactive response under five seconds; completeness may be reduced.”

The scheduler must reject detectable impossible combinations before billable work, preserve hard constraints
during optimization, and report every SLO as achieved, missed, indeterminate, or policy-blocked with evidence.

#### R-204: Routing simulation and backtesting lab — P1

- Expand `route-eval` to compare heuristics, learned policies, profiles, models, and context strategies.
- Replay versioned datasets and trace-derived workloads.
- Report quality, cloud share, privacy violations, validation/escalation rate, cost, and latency.
- Detect regressions by task class, language, provider, and hardware class.
- Export machine-readable results for CI release gates.
- Keep dataset provenance reviewable and never conflate estimated savings with measured savings.

#### R-205: Semantic response cache with coding-safe invalidation — P1

- Implement exact caching first; allow semantic reuse only for explicitly safe task classes.
- Key entries by repository state, context hashes, model/provider, prompts, tools, policy, parameters, and
  validator version.
- Isolate namespaces by project, identity, classification, and provider.
- Do not semantically reuse patches, security decisions, live diagnostics, or side-effecting tool results.
- Revalidate cached structured results when inexpensive.
- Keep cache hits visible in traces, receipts, usage, and savings reports.

#### R-206: Session- and goal-level economics — P1

- Attribute usage to session, goal, task, agent, provider, validator, tool, and cache.
- Show local-offload rate, measured/hypothetical savings, validation cost, and escalation cost.
- Support token, dollar, time, request, and optional energy budgets.
- Forecast budget exhaustion and offer policy-compliant alternatives.
- Preserve the distinction between local processing tokens and frontier tokens avoided.
- Reconcile resumed and concurrent goal totals without double-counting retries or cache hits.

#### 0.4 release gate

- Adaptive activation requires a versioned backtest and rollback target.
- Benchmarks and SLO reports are reproducible within documented tolerances.
- Security review covers feedback poisoning, routing manipulation, and cache isolation.

### 0.5 — Team, fleet, and regulated operation

Goal: support teams and on-premises compute without weakening the local-first trust model.

#### R-301: Identity-scoped policy, permissions, and budgets — P1

- Introduce identities for users, projects, CI jobs, agents, and services.
- Apply distinct model, data, tool, rate, and budget permissions.
- Support short-lived local virtual credentials instead of distributing provider keys.
- Support centrally signed organization policy that project policy cannot weaken.
- Preserve a zero-admin single-user mode.
- Evaluate identity and policy before context retrieval or provider dispatch.

#### R-302: Remote local-worker fleet — P1

- Route to developer workstations, LAN GPU servers, and on-premises clusters.
- Use mutual authentication and encrypted transport.
- Discover hardware, model, capability, health, locality, and load.
- Schedule with data classification, residency, GPU, deadline, thermal/load, and budget constraints.
- Require no inbound Internet access for isolated workers.
- Handle lost workers without duplicate side effects.

#### R-303: Encrypted storage and retention — P0

- Encrypt memory, detailed traces, cached context, receipts, and audit data at rest.
- Integrate with OS key stores and external secret managers.
- Add retention, secure purge, backup, restore, and key rotation.
- Retain hashed/minimal telemetry when detailed storage is disabled.
- Keep keys separate from encrypted data and model-visible context.

#### R-304: Tamper-evident audit and signed artifacts — P1

- Make local audit records tamper-evident.
- Sign organization policies, plugin manifests, release artifacts, and optional receipts.
- Support offline verification, rotation, and revocation.
- Export evidence packages without requiring raw prompt disclosure.
- Detect removed, reordered, or altered records and distinguish missing from invalid evidence.

#### R-305: CI and software-supply-chain integration — P1

- Add non-interactive CI mode with explicit policy and deny-by-default authority.
- Emit SARIF, JUnit, OpenTelemetry, provenance attestations, and routing reports.
- Add GitHub/GitLab checks proving how a patch was generated and validated.
- Associate output with commit, dirty state, dependencies, model identity, and receipt.
- Maintain SBOMs and signed release provenance.
- Never allow CI mode to fall back to interactive auto-approval.

#### R-306: Team operations console — P2

Provide an optional self-hosted local/on-premises UI for model/worker health, traces, routing explanations,
egress manifests, approvals, budgets, local-offload rate, SLOs, failed validation, policy drift, audit export,
retention, and incident investigation. Essential operations remain available through CLI/API. Full prompt and
source display is disabled by default and policy-controlled.

#### 0.5 release gate

- Authorization, remote workers, encryption, and audit integrity receive independent threat-model review.
- Single-user local installations remain simple and need no control-plane service.
- Upgrade, backup, restoration, and key rotation are documented and tested.

## Cross-release security workstream

These items are implemented alongside the first feature that depends on them.

### S-001: Provenance and taint tracking — P0

- Label prompts, system instructions, repository data, memory, MCP results, model output, and tool output by
  origin and trust level.
- Preserve provenance through summaries, transformations, caches, and agent handoffs.
- Prevent untrusted content from granting itself authority or being treated as policy.
- Treat prompt-injection regexes as defense-in-depth rather than the primary trust boundary.

### S-002: Secret management modernization — P0

- Prefer OS keychains, Windows Credential Manager, macOS Keychain, Vault, or short-lived credentials over
  plaintext `.env` files.
- Retain environment variables for automation with clear precedence and diagnostics.
- Scope credentials by identity, project, provider, and operation where supported.
- Keep values out of prompts, traces, crashes, receipts, and child-process environments.

### S-003: MCP and plugin trust — P0

- Pin server identity and tool-manifest hashes.
- Add per-tool scopes, response limits, deadlines, rate limits, and first-use trust review.
- Record server/tool provenance in traces and receipts.
- Detect manifest drift and re-approve material permission changes.
- Deny remote MCP/plugin network access by default.

### S-004: Network egress controls — P0

- Separate policies for models, MCP servers, tools, validators, plugins, and child processes.
- Validate destinations against redirect, DNS rebinding, loopback, and private-network confusion risks.
- Provide an offline mode with evidence that no egress occurred.

### S-005: Routing manipulation resistance — P1

- Detect attempts to force an expensive, less-private, or more-authorized tier.
- Keep privacy classification and authority independent from user routing language.
- Add adversarial router datasets and regression gates.
- Rate-limit or require approval for repeated boundary probing.

### S-006: Policy assurance tests — P1

- Express assertions such as “no cloud provider can receive these paths or classifications.”
- Generate negative tests for deny rules and privilege boundaries.
- Include policy tests in CI and configuration validation.

## Cross-release usability workstream

### U-001: Interactive setup and model operations — P1

- Add an optional `cast init` TUI showing hardware, models, downloads, VRAM, roles, and expected performance.
- Detect unloaded, stale, unavailable, or unexpectedly slow local models.
- Make downloads and external changes explicit and confirmable.

### U-002: Local observability UI — P1

- Provide a local-only UI for traces, costs, routing accuracy, local offload, validation failures, model
  health, and egress.
- Keep OpenTelemetry and JSON/CLI export first class.
- Never require a hosted CastCLI account.

### U-003: Workflow recipes — P1

- Ship recipes for local code review, private test generation, schema extraction, log triage, dependency
  review, and minimized-context cloud architecture review.
- Declare models, tools, authority, validators, outputs, and budgets.
- Make every recipe inspectable and forkable.

### U-004: Failure recovery — P1

- Resume at the failed model/tool/validator step, not only the prior commissioning wave.
- Preserve idempotency and approval semantics.
- Explain which artifacts will be reused, rerun, or invalidated.

### U-005: Notifications and diagnostics — P2

- Support local desktop, webhook, and CI notifications for budgets, worker failure, policy drift, approval,
  and long-running task completion.
- Pass outbound notification content through the Context Firewall.

## Cross-release platform and ecosystem workstream

### E-001: Stable plugin SDK — P1

Define extensions for routing signals, validators, context reducers, secret detectors, provider adapters,
capability catalogs, approval gates, audit sinks, and notifications. Require signed manifests declaring
filesystem, network, process, secret, cloud, and data permissions. Plugins remain independently inspectable,
disableable, and auditable.

### E-002: Interchange and export — P2

- Import selected LiteLLM-style provider configuration when semantics can be preserved safely.
- Export OpenTelemetry, Langfuse-compatible telemetry, routing datasets, and evidence packages.
- Publish a machine-readable model/capability catalog format.
- Do not claim compatibility when source policy semantics cannot be represented.

### E-003: Agent and workflow protocol support — P2

- Track relevant MCP and agent-to-agent standards.
- Add protocols only when identity, authority, cancellation, accounting, and provenance survive translation.
- Keep internal orchestration provider- and protocol-neutral.

## Success metrics

- Validated task success rate by task class.
- Percentage of tasks completed entirely locally.
- Cloud escalation rate and reason distribution.
- Cloud-bound source lines, bytes, and tokens per successful task.
- Privacy-policy violations, which must remain zero at release gates.
- Measured and hypothetical token/cost savings, clearly separated.
- Median and tail latency, including validator time.
- Patch apply, compile, and test pass rates.
- Tool-call and structured-output validity.
- Routing regret: cases where a cheaper eligible path would have met the SLO.
- User overrides, rejected approvals, resume success, and policy-blocked tasks.

Evaluations use equivalent fresh sessions; pin repository, model/runtime, configuration, policy, and dataset;
compare medians across repeated runs; publish sample sizes; report quality with cost; and never present a
frontier-equivalent estimate as an actual invoice reduction.

## Explicit non-goals

CastCLI will deliberately avoid:

- Chasing hundreds of provider integrations before the transparent gateway is reliable.
- Building a generic consumer chat interface.
- Claiming learned routing without measured outcomes.
- Semantic response caching for code edits before invalidation and repository isolation are rigorous.
- Broad autonomous shell access or prompt-based authorization.
- Treating JShell as an operating-system sandbox.
- Requiring a hosted control plane that weakens the local-first trust story.
- Competing primarily on microseconds of proxy overhead.
- Sending raw repositories to cloud merely because a frontier tier was selected.
- Allowing adaptive optimization to weaken hard privacy, authority, quality, or budget constraints.

## First three product bets

If development capacity requires narrowing the roadmap, implement these in order:

1. **Inbound compatibility gateway** so existing coding agents can adopt CastCLI without changing harnesses.
2. **Verified local-first escalation** so savings are backed by outcomes rather than routing guesses.
3. **Context Firewall and proof-carrying receipts** so cloud escalation is minimal, explainable, auditable,
   and reproducible.

Together these move CastCLI from a capable orchestration harness to a product with a distinct and defensible
reason to exist.

## Competitive context

Reference points include [LiteLLM](https://docs.litellm.ai/),
[Portkey](https://portkey.ai/docs/product/ai-gateway),
[Bifrost](https://docs.getbifrost.ai/overview),
[RouteLLM](https://github.com/lm-sys/routellm), [Manifest](https://manifest.build/), and
[BitRouter](https://bitrouter.ai/docs/overview/what-is-bitrouter). Their provider breadth, gateway features,
or adaptive routing make generic compatibility necessary but insufficient as CastCLI's differentiator.
