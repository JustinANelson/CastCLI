# Work checklist

## Current objective

Implement high-impact features: `cast connect` client integration generator (R-004) and resilient dynamic HTTP indexing timeouts/batching.

## Checklist

- [x] Create `ConnectService` and client connectors (`claude`, `codex`, `cursor`, `continue`, `aider`).
- [x] Add Picocli `Connect` subcommand (`CastCli.ConnectCmd`) with `--check`, `--dry-run`, `--disconnect`, `--force`, `--list`.
- [x] Add unit tests for `ConnectService` in `ConnectServiceTest`.
- [x] Add dynamic batch split-and-retry logic for vector indexing in `WorkspaceEmbeddingIndex`.
- [x] Verify implementation with `.\gradlew.bat test`, `.\gradlew.bat check`, `git diff --check`, and `mcp-usage` audit.

## Blockers and open decisions

None. Feature implementations complete and verified.

## Next action

Summary reported to user. Ready for additional tasks.

## Relevant paths

- `src/main/java/dev/justnels/castcli/connect/`
- `src/main/java/dev/justnels/castcli/CastCli.java`
- `src/main/java/dev/justnels/castcli/index/WorkspaceEmbeddingIndex.java`
- `src/test/java/dev/justnels/castcli/connect/`

## Verification status

- `.\gradlew.bat test`: passed (all 296 unit tests passed).
- `.\gradlew.bat check`: passed (checkstyle & JaCoCo 70%+ instructions coverage verified).
- `git diff --check`: passed.
- `mcp-usage` delegation audit: passed ($0.63041 estimated avoided spend recorded).
