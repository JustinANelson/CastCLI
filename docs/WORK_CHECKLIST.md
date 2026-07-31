# Work checklist

## Current objective

Implement 4 advanced speed, reliability, and efficiency capabilities (`DynamicToolSchemaPruner`, `AdaptiveFallbackPolicy`, `FileHashEmbeddingCache`, `AutoMemoryVacuum`).

## Checklist

- [x] Obtain user review and approval for `implementation_plan.md`.
- [x] Implement `DynamicToolSchemaPruner` in `DefaultToolSelector` to trim unused tool definitions and save ~3,000 prompt tokens per call.
- [x] Implement `AdaptiveFallbackPolicy` (8.0s early timeout for local tiers) in `ReliabilityExecutor`.
- [x] Implement chunk-level SHA-256 content hash caching in `WorkspaceEmbeddingIndex`.
- [x] Implement autonomous `AutoMemoryVacuum` background trigger in `SqliteMemoryStore`.
- [x] Add unit tests and verify with `.\gradlew.bat test` and `.\gradlew.bat check`.

## Blockers and open decisions

None.

## Next action

No follow-up required.
