package dev.justnels.castcli.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexerIgnoreConfigTest {
    @TempDir
    Path workspace;

    @Test
    void ensureCreatedCreatesDefaultConfigFile() throws IOException {
        Path created = IndexerIgnoreConfig.ensureCreated(workspace);
        assertThat(created).exists();
        assertThat(created.getFileName().toString()).isEqualTo("index-ignore.json");

        IndexerIgnoreConfig loaded = IndexerIgnoreConfig.load(workspace);
        assertThat(loaded.includeGitIgnore()).isTrue();
        assertThat(loaded.profiles()).contains("security", "build_artifacts", "vcs");
        assertThat(loaded.excludeGlobs()).contains("**/credentials.json", "**/*secret*", "**/.env");
    }

    @Test
    void ensureCreatedDoesNotOverwriteExistingFile() throws IOException {
        Path primary = workspace.resolve(".cast/index-ignore.json");
        Files.createDirectories(primary.getParent());
        Files.writeString(primary, """
                {
                  "includeGitIgnore": false,
                  "profiles": ["custom"],
                  "excludeGlobs": ["**/custom/**"]
                }
                """);

        Path result = IndexerIgnoreConfig.ensureCreated(workspace);
        assertThat(result).isEqualTo(primary);

        IndexerIgnoreConfig loaded = IndexerIgnoreConfig.load(workspace);
        assertThat(loaded.includeGitIgnore()).isFalse();
        assertThat(loaded.profiles()).containsExactly("custom");
        assertThat(loaded.excludeGlobs()).containsExactly("**/custom/**");
    }
}
