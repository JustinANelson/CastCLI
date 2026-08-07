# Work checklist

## Current objective

Add `cast-cli session refresh` and `cast-cli connect --refresh` / `cast-cli connect all` functionality to refresh sessions across all connected agents.

## Checklist

- [x] Add `refreshAll`, `connectAll`, and `disconnectAll` to `ConnectService`.
- [x] Support `all`, `all-connected`, and `--refresh` options in `ConnectCmd`.
- [x] Add `cast-cli session refresh` subcommand in `SessionCmd`.
- [x] Add unit tests for bulk connect, refresh, and disconnect in `ConnectServiceTest`.
- [x] Update documentation in `COMMANDS.md`.
- [x] Verify build and full test suite (`gradlew check`).
- [x] Verify patch hygiene (`git diff --check`).
- [x] Run MCP delegation audit.

## Blockers and open decisions

None.

## Next action

Task complete and verified.

## Relevant paths

- [ConnectService.java](file:///c:/Users/justnels/Projects/CastCLI/src/main/java/dev/justnels/castcli/connect/ConnectService.java)
- [CastCli.java](file:///c:/Users/justnels/Projects/CastCLI/src/main/java/dev/justnels/castcli/CastCli.java)
- [ConnectServiceTest.java](file:///c:/Users/justnels/Projects/CastCLI/src/test/java/dev/justnels/castcli/connect/ConnectServiceTest.java)
- [COMMANDS.md](file:///c:/Users/justnels/Projects/CastCLI/docs/COMMANDS.md)

## Verification status

- `.\gradlew.bat test`: passed.
- `.\gradlew.bat check`: passed (including SpotBugs, Checkstyle, and JaCoCo coverage).
- `git diff --check`: passed.
- Delegation audit: passed.
