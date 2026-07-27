package dev.justnels.castcli.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryToolsTest {

    @TempDir
    Path workspace;

    @Test
    void storesAndRecallsMemories() throws Exception {
        MemoryTools memoryTools = new MemoryTools(workspace);

        String storeResult = memoryTools.rememberContext("auth-flow", "Use OAuth2 PKCE for mobile clients.", "Claude-Code");
        assertThat(storeResult).contains("auth-flow");

        Path database = workspace.resolve(".cast").resolve("memory").resolve("memory.db");
        assertThat(database).isRegularFile();

        String recallResult = memoryTools.recallContext("auth", 5);
        assertThat(recallResult).contains("auth-flow", "Use OAuth2 PKCE for mobile clients.");
    }

    @Test
    void validatesInput() {
        MemoryTools memoryTools = new MemoryTools(workspace);

        assertThatThrownBy(() -> memoryTools.rememberContext("", "insight", "author"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> memoryTools.rememberContext("topic", "", "author"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
