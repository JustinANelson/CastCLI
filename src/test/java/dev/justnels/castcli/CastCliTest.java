package dev.justnels.castcli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
