# Work checklist

## Current objective

Fix `cast-cli index` HTTP timeout exceptions when indexing repositories by making embedding batch sizes, HTTP retries, and timeout parameters configurable with resilient defaults.

## Checklist

- [x] Update `EmbeddingConfig.java` to add `maxBatchSize` (default 16) and `maxRetries` (default 1) with backward-compatible constructors.
- [x] Update `EmbeddingModelFactory.java` to pass `maxRetries` to `OpenAiEmbeddingModel`.
- [x] Update `WorkspaceEmbeddingIndex.java` to use `config.maxBatchSize()` instead of hardcoded 64.
- [x] Update `ConfigValidator.java` to validate `maxBatchSize` and `maxRetries`.
- [x] Update and add unit tests in `EmbeddingConfigTest.java` and `WorkspaceEmbeddingIndexTest.java`.
- [x] Verify implementation with `.\gradlew.bat test`, `.\gradlew.bat check`, `git diff --check`, and MCP delegation audit.

## Blockers and open decisions

None. Feature implementation and verification complete.

## Next action

Summary reported to user.

## Relevant paths

- `src/main/java/dev/justnels/castcli/config/EmbeddingConfig.java`
- `src/main/java/dev/justnels/castcli/config/ConfigValidator.java`
- `src/main/java/dev/justnels/castcli/model/EmbeddingModelFactory.java`
- `src/main/java/dev/justnels/castcli/index/WorkspaceEmbeddingIndex.java`
- `src/test/java/dev/justnels/castcli/`

## Verification status

- `.\gradlew.bat test`: passed.
- `.\gradlew.bat check`: passed.
- `git diff --check`: passed.
- `mcp-usage` audit: passed.
