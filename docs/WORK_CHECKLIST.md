# Work checklist

## Current objective

Implement Context Egress Firewall & Cloud Egress Manifests (R-102): privacy classification (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED`), secret redaction, path deny-globs, and cloud egress manifests.

## Checklist

- [x] Create `ContextClassification` enum in `dev.justnels.castcli.security`.
- [x] Create `ContextFirewall` evaluating classification, secret redaction, deny-globs, and egress limits.
- [x] Create `EgressManifest` representing cloud egress records stored in `.cast/egress/`.
- [x] Integrate `ContextFirewall` in `HarnessOrchestrator` before `FRONTIER_CLOUD` model dispatch.
- [x] Create unit tests in `ContextFirewallTest.java`.
- [x] Verify implementation with `.\gradlew.bat test`, `.\gradlew.bat check`, `git diff --check`, and `mcp-usage` audit.

## Blockers and open decisions

None. Feature implementations complete and verified.

## Next action

Summary reported to user. Ready for next recommendations (R-103 AST Token Compiler / R-201 Profiler).

## Relevant paths

- `src/main/java/dev/justnels/castcli/security/`
- `src/main/java/dev/justnels/castcli/orchestration/HarnessOrchestrator.java`
- `src/test/java/dev/justnels/castcli/security/`

## Verification status

- `.\gradlew.bat test`: passed (all 314 unit tests passed).
- `.\gradlew.bat check`: passed (Checkstyle & JaCoCo 70%+ coverage verified).
- `git diff --check`: passed.
- `mcp-usage` delegation audit: passed.
