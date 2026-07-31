# Work checklist

## Current objective

Fix vector dimension caching on store load, terminal stream buffer flushing, and progress listener callbacks for `cast-cli index`.

## Checklist

- [x] Create `ShardedEmbeddingStore.java` to handle bucketed shard partitions and selective dirty shard re-serialization.
- [x] Invoked CastCLI MCP delegation tools (`generate_tests`, `summarize_files`, `map_change_impact`, `review_diff`).
- [x] Create `ShardedEmbeddingStoreTest.java` to test sharded store creation, search, and dirty shard serialization.
- [x] Modify `WorkspaceEmbeddingIndex.java` to integrate `ShardedEmbeddingStore` for index rebuilds and searches.
- [x] Update all hardware preset JSON configuration files (`harness.vram-*.json`, `harness.apple-silicon.json`) to include enabled embedding configurations by default.
- [x] Update `InitService.java` to inspect enabled embedding models when probing local model requirements.
- [x] Optimize directory traversal in `WorkspaceEmbeddingIndex.java` with `Files.walkFileTree` directory pruning (`SKIP_SUBTREE`).
- [x] Fix vector dimension probing in `ShardedEmbeddingStore.java` when loading existing shards from disk so zero-vector filter scans use matching vector dimensions.
- [x] Terminate stale background Java processes holding locks on `build/install/cast-cli/lib/*.jar` and force full distribution re-installation.
- [x] Add `onFileStarted` callback to `ProgressListener` in `WorkspaceEmbeddingIndex.java` to render active status as soon as a file begins processing/embedding.
- [x] Update `CastCli.java` `IndexProgressPrinter` with `System.err.flush()` on every print so Java's `PrintStream` buffer never delays rendering `\r` line updates on PowerShell/Windows terminals.
- [x] Verify implementation with `.\gradlew.bat test`, `.\gradlew.bat check`, `.\gradlew.bat installDist`, and `git diff --check`.
- [x] Perform CastCLI delegation audit (`.\gradlew.bat run --args="..."`).

## Blockers and open decisions

None. All issues resolved and verified.

## Next action

Report completion to user.

## Relevant paths

- `src/main/java/dev/justnels/castcli/index/ShardedEmbeddingStore.java`
- `src/main/java/dev/justnels/castcli/index/WorkspaceEmbeddingIndex.java`
- `src/main/java/dev/justnels/castcli/CastCli.java`

## Verification status

- `.\gradlew.bat test`: passed.
- `.\gradlew.bat check`: passed.
- `.\gradlew.bat installDist`: passed.
- `git diff --check`: passed cleanly.
- `mcp-usage` delegation audit: passed.
