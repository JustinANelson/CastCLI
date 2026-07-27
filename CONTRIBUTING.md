# Contributing to CastCLI

## Setup

Requires JDK 21. No other install step is required — the Gradle wrapper bootstraps everything else.

```powershell
Copy-Item config/harness.example.json config/harness.local.json
./gradlew.bat test
```

## Development loop

- Build the CLI distribution after any change under `src/main`:
  `./gradlew.bat installDist` (the `mcp-serve` registration and any manual CLI testing run this
  compiled output, not source).
- Run the full test suite: `./gradlew.bat test`.
- Run checkstyle (the same checks CI runs): `./gradlew.bat checkstyleMain checkstyleTest`.
- `./gradlew.bat build` runs tests, checkstyle, and coverage verification, then builds the
  distribution — this is the closest local equivalent to CI.
- Coverage is enforced via JaCoCo at a 70% minimum (`build.gradle.kts`); add tests alongside new
  code rather than relying on existing coverage headroom.
- If you touch anything under `config/`, keep model names in sync with what `ollama list` actually
  reports locally — a typo'd tag fails silently by falling back to another routing tier instead of
  erroring.

## Pull requests

- Keep PRs focused; a bug fix doesn't need accompanying refactors.
- Add or update tests for behavior you change.
- Update `README.md`/`docs/` when you change a default, a file path, or a CLI flag — stale docs are
  treated as bugs here (see `SECURITY.md`/`docs/OPERATIONS.md` for the kind of drift we've hit
  before between documented and actual `.cast/` paths).
- CI runs `./gradlew.bat check test checkstyleMain checkstyleTest`, coverage verification, an
  `installDist` smoke test, and a Docker build/health-check — make sure these pass locally first.

## Versioning

CastCLI is pre-1.0 (see [SECURITY.md](SECURITY.md#supported-versions)): breaking changes to config
shape or CLI flags can still land on `main` between releases. Once 1.0 ships, releases follow
[SemVer](https://semver.org/) against two things specifically:

- **Config file shape** — fields read from `config/*.json` (`ConfigLoader`/`HarnessConfig`).
- **CLI surface** — subcommands and flags in `CastCli.java`.

A breaking change to either bumps the major version post-1.0; anything else (new optional config
field, new flag, bug fix) is minor/patch. Release notes live in [CHANGELOG.md](CHANGELOG.md) —
update it alongside any user-visible change in the same PR that makes it, not as a follow-up.

The release workflow (`.github/workflows/release.yml`) derives the packaged version from the
pushed git tag (`vX.Y.Z` -> `X.Y.Z`), so tagging is what actually cuts a release; `build.gradle.kts`'s
`0.1.0-SNAPSHOT` default is only used for local/dev builds.

## Reporting bugs vs. security issues

Use GitHub Issues for functional bugs. For anything touching workspace confinement, the
approval-gate/write-exec boundary, or secret handling, follow [SECURITY.md](SECURITY.md) instead of
filing a public issue.
