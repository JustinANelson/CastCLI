package dev.justnels.castcli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CastCliTest {

    // Deliberately not "config/harness.local.json": that relative path can genuinely exist under this
    // repo's real working directory (a developer's own gitignored local config), which would make the
    // fast-path check in resolveConfigPath short-circuit before ever consulting the startDir argument
    // under test. A unique relative path guarantees the fast-path check misses and the search runs.
    private static final Path RELATIVE_CONFIG = Path.of("castcli-test-config-9f3e", "harness.local.json");

    @Test
    void findsConfigInStartingDirectory(@TempDir Path root) throws Exception {
        Path configDir = Files.createDirectories(root.resolve(RELATIVE_CONFIG.getParent()));
        Path configFile = Files.writeString(configDir.resolve(RELATIVE_CONFIG.getFileName()), "{}");

        Path resolved = CastCli.resolveConfigPath(RELATIVE_CONFIG, root);

        assertThat(resolved).isEqualTo(configFile);
    }

    @Test
    void searchesUpwardFromASubdirectory(@TempDir Path root) throws Exception {
        Path configDir = Files.createDirectories(root.resolve(RELATIVE_CONFIG.getParent()));
        Path configFile = Files.writeString(configDir.resolve(RELATIVE_CONFIG.getFileName()), "{}");
        Path nested = Files.createDirectories(root.resolve("src").resolve("main"));

        Path resolved = CastCli.resolveConfigPath(RELATIVE_CONFIG, nested);

        assertThat(resolved).isEqualTo(configFile);
    }

    @Test
    void fallsBackToTheGivenRelativePathWhenNothingIsFound(@TempDir Path root) {
        Path resolved = CastCli.resolveConfigPath(RELATIVE_CONFIG, root);

        assertThat(resolved).isEqualTo(RELATIVE_CONFIG);
    }

    @Test
    void leavesAbsolutePathsUntouched(@TempDir Path root) {
        Path absolute = root.resolve("somewhere-else.json");

        Path resolved = CastCli.resolveConfigPath(absolute, root);

        assertThat(resolved).isEqualTo(absolute);
    }

    @Test
    void bootstrapAnchorFindsTheNearestAncestorGitDirectory(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Path nested = Files.createDirectories(root.resolve("src").resolve("main"));

        Path anchor = CastCli.resolveBootstrapAnchor(nested);

        assertThat(anchor).isEqualTo(root);
    }

    @Test
    void bootstrapAnchorFallsBackToStartDirWhenNoGitDirectoryIsFound(@TempDir Path root) {
        Path anchor = CastCli.resolveBootstrapAnchor(root);

        assertThat(anchor).isEqualTo(root);
    }

    @Test
    void registersNativeFeatureCommand() {
        assertThat(new CommandLine(new CastCli()).getSubcommands()).containsKey("feature");
    }

    @Test
    void featurePromptStandardizesImplementationAndVerification() {
        String prompt = CastCli.Feature.buildPrompt("Add task filtering");

        assertThat(prompt).contains("Add task filtering", "Preserve unrelated changes",
                "add or update focused tests", "Do not use cloud providers");
    }

    @Test
    void featureDryRunUsesLocalConfigWithoutInvokingModels(@TempDir Path root) throws Exception {
        Path config = writeFeatureConfig(root, "SMALL_LOCAL");

        int exitCode = new CommandLine(new CastCli()).execute(
                "--config", config.toString(), "feature", "--dry-run", "Add", "task", "filtering");

        assertThat(exitCode).isZero();
    }

    @Test
    void featureRefusesEnabledCloudProvidersEvenInDryRun(@TempDir Path root) throws Exception {
        Path config = writeFeatureConfig(root, "FRONTIER_CLOUD");

        int exitCode = new CommandLine(new CastCli()).execute(
                "--config", config.toString(), "feature", "--dry-run", "Add", "task", "filtering");

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void featureFingerprintDetectsRealFilesButIgnoresCastRuntimeArtifacts(@TempDir Path root) throws Exception {
        byte[] initial = CastCli.Feature.workspaceFingerprint(root, 100_000);
        Files.createDirectories(root.resolve(".cast/checkpoints"));
        Files.writeString(root.resolve(".cast/checkpoints/run.json"), "runtime only");
        byte[] runtimeOnly = CastCli.Feature.workspaceFingerprint(root, 100_000);
        Files.writeString(root.resolve("index.html"), "<h1>Chat</h1>");
        byte[] workspaceChanged = CastCli.Feature.workspaceFingerprint(root, 100_000);

        assertThat(Arrays.equals(initial, runtimeOnly)).isTrue();
        assertThat(Arrays.equals(initial, workspaceChanged)).isFalse();
    }

    private static Path writeFeatureConfig(Path root, String tier) throws Exception {
        Path config = root.resolve("harness.json");
        Files.writeString(config, """
                {
                  "providers": [{
                    "id": "only-provider",
                    "tier": "%s",
                    "baseUrl": "http://localhost:11434/v1/",
                    "modelName": "test-model",
                    "temperature": 0.1,
                    "timeoutSeconds": 30,
                    "enabled": true
                  }],
                  "tools": {
                    "workspaceRoot": "%s",
                    "maxFileBytes": 1048576,
                    "jshellEnabled": false,
                    "allowWrites": true,
                    "allowShellExec": true,
                    "requireApproval": true
                  }
                }
                """.formatted(tier, root.toString().replace("\\", "\\\\")));
        return config;
    }
}
