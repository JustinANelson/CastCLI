class CastCli < Formula
  desc "Java LLM orchestration harness: routes tasks between local Ollama models and frontier cloud models"
  homepage "https://github.com/JustinANelson/CastCLI"
  version "0.1.2"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/JustinANelson/CastCLI/releases/download/v#{version}/cast-cli-macos-arm64.zip"
      sha256 "8295f815b6686b145a5c51975490c26e99583bb79b4fce63f9105e007231cf60"
    end
    on_intel do
      url "https://github.com/JustinANelson/CastCLI/releases/download/v#{version}/cast-cli-macos-x64.zip"
      sha256 "fac32c6aabad0a0ace4b0031a946049853cceb37749510edbd3fb8e399d1d521"
    end
  end

  on_linux do
    url "https://github.com/JustinANelson/CastCLI/releases/download/v#{version}/cast-cli-linux-x64.zip"
    sha256 "d4bcffb9735564239af3c49d0b25e524a92edf5ec65e669542184287ce590461"
  end

  # These are self-contained jpackage app-images (embedded JRE) rather than a jar CastCLI builds
  # from source at install time -- no `depends_on "openjdk"` needed, and there is nothing to build.
  # Homebrew's normal "prefer building from source" preference doesn't apply here for the same
  # reason it doesn't for e.g. most Go/Rust formulae: the upstream release *is* the artifact.

  def install
    if OS.mac?
      libexec.install "cast-cli.app"
      bin.install_symlink libexec/"cast-cli.app/Contents/MacOS/cast-cli"
    else
      libexec.install Dir["cast-cli/*"]
      bin.install_symlink libexec/"bin/cast-cli"
    end
  end

  def caveats
    <<~EOS
      cast-cli embeds its own Java runtime; no separate JDK/JRE install is required.

      First run in a project:
        cast-cli init      # detects your GPU/VRAM (or Apple Silicon) and writes .cast/harness.local.json
        cast-cli doctor    # sanity-checks config, provider endpoints, and local model availability
    EOS
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/cast-cli --version")
  end
end
