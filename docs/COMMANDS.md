# Command reference

All commands run from the repository root. On macOS/Linux replace `gradlew.bat` with `./gradlew`.

## Setup and verification

```powershell
# Preferred: detect hardware/Ollama and write a matching starter config
./gradlew.bat installDist
./build/install/cast-cli/bin/cast-cli.bat init
# ./build/install/cast-cli/bin/cast-cli.bat init --preset 8gb   # skip detection
# ./build/install/cast-cli/bin/cast-cli.bat init --force        # overwrite an existing config

# Or manually copy a preset/example config instead
Copy-Item config/harness.example.json config/harness.local.json

# Show Java and Gradle versions
java -version
./gradlew.bat --version

# Compile, test, and build the distribution
./gradlew.bat clean build

# Run tests only (HTML report: build/reports/tests/test/index.html)
./gradlew.bat test

# Generate coverage (HTML report: build/reports/jacoco/test/html/index.html)
./gradlew.bat jacocoTestReport

# Opt-in deterministic performance and resilience lanes
./gradlew.bat performanceTest
./gradlew.bat chaosTest

```

Gradle uses a Java 21 toolchain and can download a matching JDK through the Foojay resolver when needed.

## Local models with Ollama

```powershell
ollama serve
ollama pull qwen3:4b
ollama pull qwen3:30b
ollama list
```

Model names are examples, not hard requirements. Any server with an OpenAI-compatible `/v1/chat/completions` endpoint can be configured by changing `baseUrl` and `modelName`.

## Harness CLI

```powershell
# Help and configured-provider status
./gradlew.bat run --args="--help"
./gradlew.bat run --args="models"
./gradlew.bat run --args="models --probe"

# Automatic routing
./gradlew.bat run --args='ask "Summarize the purpose of Java records"'

# Multi-agent commissioning pipeline
./gradlew.bat run --args='commission "Implement a thread-safe LRU Cache in Java"'
./gradlew.bat run --args='--config config/harness.vram-12gb.json commission "Design a REST API endpoint"'
./gradlew.bat run --args='--config config/harness.local-only.json commission "Implement a local-only feature"'

# One-command local-only feature implementation
./gradlew.bat run --args='--config config/harness.local-only.json feature "Add task filtering"'
./gradlew.bat run --args='--config config/harness.local-only.json feature --dry-run "Add task filtering"'
./gradlew.bat run --args='--config config/harness.local-only.json feature --yes "Add task filtering"'

# Give the router a workload hint
./gradlew.bat run --args='ask --workload QUICK "Write a short title"'
./gradlew.bat run --args='ask --workload CODE "Inspect the project and suggest a refactor"'
./gradlew.bat run --args='ask --workload REASONING "Compare these two architectures"'

# Force a tier
./gradlew.bat run --args='ask --tier LARGE_LOCAL "Explain this Gradle build"'

# Stream tokens as they arrive (no tools on this path)
./gradlew.bat run --args='ask --stream "Write a haiku about build systems"'

# Auto-approve write/exec tool calls instead of an interactive y/N prompt
./gradlew.bat run --args='ask --yes "Create a scratch file with today'"'"'s date"'

# Resume a commission run from its last checkpoint (path printed at the end of a prior run)
./gradlew.bat run --args='commission --resume .cast/checkpoints/<hash>.json'

# Run as an MCP server over stdio, exposing only SMALL_LOCAL/LARGE_LOCAL tiers as tools
# (point an MCP-capable client such as Claude Code at this as a stdio server)
./gradlew.bat run --args="mcp-serve"

# Connect CastCLI to external client configuration (claude, codex, cursor, continue, aider, antigravity / agy)
./gradlew.bat run --args="connect agy"
./gradlew.bat run --args="connect antigravity --dry-run"
./gradlew.bat run --args="connect agy --disconnect"

# Run the bounded OpenAI-compatible gateway; limits have conservative defaults and are independently tunable
./gradlew.bat run --args='gateway --port 8081 --max-request-bytes 1048576 --max-concurrent-requests 16'
./gradlew.bat run --args='gateway --max-concurrent-streams 8 --queue-wait-ms 2000 --max-messages 256'

# Additional structural limits: --max-tools, --max-json-depth, and --max-string-chars


# Verify MCP utilization and local token/cost efficiency
./gradlew.bat run --args="mcp-usage --since-hours 24 --fail-if-unused"
./gradlew.bat run --args="mcp-usage --since-hours 1 --baseline-codex-tokens 12000 --delegated-codex-tokens 7800"

# Build/refresh the semantic workspace search index (requires embeddings.enabled=true in config)
./gradlew.bat run --args="index"

# Query the existing index directly, without going through a model's tool-calling
./gradlew.bat run --args='index --query "where do we validate matchmaking tickets" --max-results 5'

# Inspect and curate transactional shared memory
./gradlew.bat run --args='memory remember architecture "Use PostgreSQL in production" --tags database'
./gradlew.bat run --args='memory recall "production database" --limit 5'
./gradlew.bat run --args='memory list --limit 20'
./gradlew.bat run --args='memory forget <id> --expected-version <version>'

# Session action summarization and long-term memory turnover
./gradlew.bat run --args='session summarize --session-id s1 --role Coder "Implemented feature X"'
./gradlew.bat run --args='session recall "feature X"'

# Evaluate routing and tool-selection policy without calling a model
./gradlew.bat run --args='route-eval evals/routing.example.json'

# Use a different configuration file. Root options precede the subcommand.
./gradlew.bat run --args='--config config/experiment.json models'
```

Valid workloads are `AUTO`, `QUICK`, `CODE`, and `REASONING`. Valid tiers are `SMALL_LOCAL`, `LARGE_LOCAL`, and `FRONTIER_CLOUD`.

Generate shell completion for your current shell:

```powershell
cast-cli completion --shell bash
cast-cli completion --shell zsh
cast-cli completion --shell powershell
```

Write and process-exec tools (`writeWorkspaceFile`, `runCommand`) only activate when `tools.allowWrites` /
`tools.allowShellExec` are `true` in the harness config, and by default still pause for an interactive
`y/N` confirmation on every call; pass `--yes` to auto-approve instead (do this only in trusted, unattended
contexts). `commission` prints its checkpoint path after every run — pass it back via `--resume` to continue
a crashed or interrupted pipeline without re-running completed subtasks.

## Cloud credentials

`feature` reuses the commissioning pipeline with a standardized implementation, test, and verification
prompt. It refuses every config with an enabled `FRONTIER_CLOUD` provider, even for `--dry-run`, and a real
run requires both write and process-exec tools. Run it from the target project's root so CastCLI anchors
the generated task and workspace tools to that project.
Exact standalone text-form tool calls from local models are executed through the normal approval gate.
A run that changes only `.cast` runtime state fails rather than claiming that a feature was implemented.

Set the variable referenced by a provider's `apiKeyEnv`, then enable that provider in local configuration:

```powershell
$env:OPENAI_API_KEY = "your-key-for-this-shell"
./gradlew.bat run --args="models"
./gradlew.bat run --args='ask --tier FRONTIER_CLOUD "Analyze this design"'
```

Do not commit `config/harness.local.json` or `.env`; both are ignored.

## Distribution and direct execution

```powershell
./gradlew.bat installDist
./build/install/cast-cli/bin/cast-cli.bat models
./build/install/cast-cli/bin/cast-cli.bat ask "Hello"
```

## Useful maintenance commands

```powershell
./gradlew.bat tasks
./gradlew.bat dependencies
./gradlew.bat dependencyUpdates   # only after adding a dependency-update plugin
./gradlew.bat clean
```
