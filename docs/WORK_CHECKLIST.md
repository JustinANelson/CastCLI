# Work checklist

## Current objective

Add a native, one-command local-only feature implementation workflow to CastCLI.

## Checklist

- [x] Add `cast-cli feature <description>` as a first-class command.
- [x] Reuse the commissioning pipeline and standardized implementation prompt.
- [x] Enforce local-only providers and required write/exec capabilities.
- [x] Add `--dry-run`, `--yes`, focused tests, and command documentation.
- [x] Run focused tests, diff review, full check, patch hygiene, and installed-distribution verification.

## Blockers and open decisions

None. The required CastCLI local impact/test helpers were attempted, but this session's MCP server is
scoped to CastHarness and could not read CastCLI paths. Direct inspection and verification are being used.

## Next action

Report completion. The native command is available in the rebuilt installed distribution.

## Relevant paths

- `src/main/java/dev/justnels/castcli/CastCli.java`
- `src/test/java/dev/justnels/castcli/CastCliTest.java`
- `README.md`
- `docs/COMMANDS.md`
- `config/harness.local-only.json`

## Verification status

- `compileJava`: passed.
- Focused `CastCliTest`: passed; the filtered-run aggregate coverage gate failed as expected.
- Full `check`: passed with aggregate coverage verification.
- `checkstyleMain`: passed with one unrelated pre-existing warning.
- Installed distribution: rebuilt.
- Installed `--help` discovery and CastHarness `feature --dry-run`: passed.
