# Packaging

Package manager metadata for distributing pre-built `cast-cli` releases. These files are the
source of truth kept in this repo; each target package manager needs its own separate repo to
actually serve installs from (neither Homebrew nor Scoop can install directly from an arbitrary
path in this repo).

## Homebrew (`homebrew/cast-cli.rb`)

Homebrew requires formulae to live in a "tap" repo named `homebrew-<name>`. One-time setup:

1. Create a new GitHub repo named `homebrew-cast-cli` (or `homebrew-tap` if you want a shared tap
   for future formulae too).
2. Add `Formula/cast-cli.rb` to it, copied from `packaging/homebrew/cast-cli.rb` here.
3. Users then install with:
   ```sh
   brew tap justinanelson/cast-cli
   brew install cast-cli
   ```
4. Verify before publishing: `brew install --build-from-source ./Formula/cast-cli.rb` and
   `brew audit --strict --online cast-cli` on a Mac (and ideally Linux, since the formula also
   covers Linuxbrew) -- this hasn't been run yet since neither is available in this environment.

## Scoop (`scoop/cast-cli.json`)

Scoop installs from a "bucket" repo. One-time setup:

1. Create a new GitHub repo named `scoop-bucket` (or similar).
2. Add `bucket/cast-cli.json` to it, copied from `packaging/scoop/cast-cli.json` here.
3. Users then install with:
   ```powershell
   scoop bucket add cast-cli https://github.com/JustinANelson/scoop-bucket
   scoop install cast-cli
   ```

## Keeping both current on every release

Both files hardcode `version`, download URLs, and SHA256 hashes for `v0.1.2` -- they will drift
immediately on the next release and need updating (or automation) every time a new tag is pushed:

- Homebrew: the `on_macos`/`on_linux` blocks' `sha256` values must match the new release's
  `cast-cli-{macos-arm64,macos-x64,linux-x64}.zip.sha256` files.
- Scoop's `autoupdate` block *does* handle this automatically for the version/URL/hash -- a bot run
  (e.g. `Add-ScoopBucketRepo` + `checkver`/`autoupdate` GitHub Action, the standard pattern most
  buckets use) can bump `scoop/cast-cli.json` without manual edits, since it can compute the hash
  from `$url.sha256` itself.
- A stale tap/bucket (pointing at a version whose GitHub release assets have since rotated, or just
  visibly behind) actively damages trust more than not having one -- worth wiring up the release
  workflow to push the updated formula/manifest to their respective repos automatically once the
  tap/bucket repos exist, rather than relying on remembering to do it by hand.
