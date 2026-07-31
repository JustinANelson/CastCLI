package dev.justnels.castcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMemoryCleanerTest {

    @TempDir Path tempDir;

    @Test
    void consolidatesDuplicateSessionEntries() {
        SqliteMemoryStore store = new SqliteMemoryStore(tempDir.resolve("memory.db"));
        store.put(new MemoryDraft("session", "session-turnover", "session-summary:s1", "Initial session work completed", "Agent", "cli", List.of(), 0.5, 0.5, null, false, null));
        store.put(new MemoryDraft("session", "session-turnover", "session-summary:s1", "Followup session work completed", "Agent", "cli", List.of(), 0.5, 0.5, null, false, null));

        LocalMemoryCleaner cleaner = new LocalMemoryCleaner(store, null, "session");
        LocalMemoryCleaner.CleaningReport report = cleaner.cleanAndConsolidate();

        assertThat(report.totalInspected()).isEqualTo(2);
        assertThat(report.entriesConsolidated()).isEqualTo(1);
        assertThat(store.list("session", 10)).hasSize(1);
    }
}
