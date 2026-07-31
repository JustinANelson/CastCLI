# Work checklist

## Current objective

Implement efficiency recommendations for session memory turnover (on-demand semantic retrieval & multi-namespace pre-flight prompt augmentation).

## Checklist

- [x] Update `MemoryContextProvider` to perform multi-namespace hybrid search across project and session memory with strict context bounds.
- [x] Refine `AGENTS.md` instructions for query-driven semantic session turnover retrieval.
- [x] Add unit test verifying multi-namespace prompt augmentation in `MemoryContextProviderTest`.
- [x] Run `.\gradlew.bat test` and `.\gradlew.bat check` to verify changes.

## Blockers and open decisions

None.

## Next action

No follow-up required.
