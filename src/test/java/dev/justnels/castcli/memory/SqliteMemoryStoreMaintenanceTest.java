package dev.justnels.castcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteMemoryStoreMaintenanceTest {

    @Test
    void testBackupAndOptimize(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("memory.db");
        SqliteMemoryStore store = new SqliteMemoryStore(dbPath);

        MemoryDraft draft = new MemoryDraft("default", "project", "test-topic", "test-content", "author", "source", java.util.List.of("tag"), 0.8, 0.9, null, false, null);
        store.put(draft);

        Path backupPath = tempDir.resolve("backups/memory-backup.db");
        store.backup(backupPath);
        assertTrue(Files.exists(backupPath), "Backup file should exist");
        assertTrue(Files.size(backupPath) > 0, "Backup file should not be empty");

        store.optimize();
    }
}
