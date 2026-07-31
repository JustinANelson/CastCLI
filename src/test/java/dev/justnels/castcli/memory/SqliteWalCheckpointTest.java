package dev.justnels.castcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;

class SqliteWalCheckpointTest {

    @TempDir Path tempDir;

    @Test
    void executesWalCheckpointAndOptimizeWithoutError() {
        SqliteMemoryStore store = new SqliteMemoryStore(tempDir.resolve("test-memory.db"));

        assertThatCode(() -> {
            store.optimize();
            store.checkpointWal();
        }).doesNotThrowAnyException();
    }
}
