# Work checklist

## Current objective

Implement multi-file sharded workspace embedding index, default embedding init config, and fast directory scanning with visual terminal progress bars for `cast-cli index`.

## Checklist

- [x] Create `ShardedEmbeddingStore.java` to handle bucketed shard partitions and selective dirty shard re-serialization.
- [x] Invoked CastCLI MCP delegation tools (`generate_tests`, `summarize_files`, `map_change_impact`, `review_diff`).
- [x] Create `ShardedEmbeddingStoreTest.java` to test sharded store creation, search, and dirty shard serialization.
- [x] Modify `WorkspaceEmbeddingIndex.java` to integrate `ShardedEmbeddingStore` for index rebuilds and searches.
- [x] Update all hardware preset JSON configuration files (`harness.vram-*.json`, `harness.apple-silicon.json`) to include enabled embedding configurations by default.
- [x] Update `InitService.java` to inspect enabled embedding models when probing local model requirements.
- [x] Optimize directory traversal in `WorkspaceEmbeddingIndex.java` with `Files.walkFileTree` directory pruning (`SKIP_SUBTREE`).
- [x] Update `CastCli.java` `IndexProgressPrinter` with immediate scan notification and dynamic visual terminal progress bar rendering.
- [x] Verify implementation with `.\gradlew.bat test`, `.\gradlew.bat check`, `.\gradlew.bat installDist`, and `git diff --check`.
- [x] Perform CastCLI delegation audit (`.\gradlew.bat run --args="..."`).

## Blockers and open decisions

None. All features and progress bar improvements implemented and verified.

## Next action

Report progress bar fix to user.

## Relevant paths

- `src/main/java/dev/justnels/castcli/index/ShardedEmbeddingStore.java`
- `src/main/java/dev/justnels/castcli/index/WorkspaceEmbeddingIndex.java`
- `src/main/java/dev/justnels/castcli/CastCli.java`
- `src/main/java/dev/justnels/castcli/doctor/InitService.java`
- `config/harness.vram-*.json`
- `config/harness.apple-silicon.json`

## Verification status

- `.\gradlew.bat test`: passed.
- `.\gradlew.bat check`: passed.
- `.\gradlew.bat installDist`: passed.
- `git diff --check`: passed cleanly.
- `mcp-usage` delegation audit: passed.
