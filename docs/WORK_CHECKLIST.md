# Work checklist

## Current objective

Add `cast-cli connect` support for Antigravity (`agy` / `antigravity`).

## Checklist

- [x] Create `AntigravityConnector` targeting `.agents/mcp.json` (and `.gemini/mcp.json`).
- [x] Register `AntigravityConnector` and `agy` alias in `ConnectService`.
- [x] Update `CastCli` command help annotations and documentation.
- [x] Add unit tests in `ConnectServiceTest` for `antigravity` and `agy`.
- [x] Verify build and full test suite (`gradlew check`).
- [x] Verify patch hygiene (`git diff --check`).
- [x] Run MCP delegation audit.

## Blockers and open decisions

None.

## Next action

Task complete and verified.

## Relevant paths

- [AntigravityConnector.java](file:///c:/Users/justnels/Projects/CastCLI/src/main/java/dev/justnels/castcli/connect/AntigravityConnector.java)
- [ConnectService.java](file:///c:/Users/justnels/Projects/CastCLI/src/main/java/dev/justnels/castcli/connect/ConnectService.java)
- [CastCli.java](file:///c:/Users/justnels/Projects/CastCLI/src/main/java/dev/justnels/castcli/CastCli.java)
- [ConnectServiceTest.java](file:///c:/Users/justnels/Projects/CastCLI/src/test/java/dev/justnels/castcli/connect/ConnectServiceTest.java)
- [COMMANDS.md](file:///c:/Users/justnels/Projects/CastCLI/docs/COMMANDS.md)

## Verification status

- `.\gradlew.bat test`: passed.
- `.\gradlew.bat check`: passed (including SpotBugs, Checkstyle, and JaCoCo coverage).
- `git diff --check`: passed.
- Delegation audit: passed.
