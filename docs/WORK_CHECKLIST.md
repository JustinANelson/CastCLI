# Work checklist

## Current objective

Add structured multi-agent coordination to CastCLI: canonical project state, atomic task leases,
dependency and file-overlap controls, structured handoffs, MCP tools, tests, and documentation.

## Checklist

- [x] Add schema migration for coordination state, tasks, and handoffs.
- [x] Implement transactional project state, dependency-aware tasks, atomic claims, heartbeats, stale lease takeover,
  overlap warnings, structured handoffs, and snapshots.
- [x] Register coordination MCP tools and schemas.
- [x] Add focused persistence and MCP tests.
- [x] Update agent and MCP workflow documentation.
- [x] Run full test/check validation and review the final diff.
- [x] Apply the verified patch to the primary CastCLI repository and rebuild `installDist`.
- [x] Update CastHarness agent guidance and verify the new tools against its database.

## Blockers and open decisions

None.

## Next action

Report completion to the user.

## Relevant paths

- `src/main/java/dev/justnels/castcli/memory/CoordinationStore.java`
- `src/main/java/dev/justnels/castcli/memory/SqliteMemoryMigrator.java`
- `src/main/java/dev/justnels/castcli/mcp/McpStdioServer.java`
- `src/test/java/dev/justnels/castcli/memory/CoordinationStoreTest.java`
- `src/test/java/dev/justnels/castcli/mcp/McpStdioServerTest.java`
- `AGENTS.md`
- `docs/CODEX_MCP.md`

## Verification status

- `compileJava`: passed.
- Focused coordination/MCP tests: passed; the command's finalized aggregate coverage check correctly requires the full suite.
- `test`: passed with aggregate coverage verification.
- `check`: passed.
- `installDist`: passed in the primary CastCLI repository.
- Installed MCP `tools/list` and `coordination_snapshot`: passed against CastHarness.
