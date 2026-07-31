package dev.justnels.castcli.memory;

import dev.justnels.castcli.config.MemoryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryContextProviderTest {
    @TempDir Path tempDir;

    @Test
    void injectsRelevantMemoryWithinBudget() {
        SqliteMemoryStore store = new SqliteMemoryStore(tempDir.resolve("memory.db"));
        store.put(MemoryDraft.shared("project", "database", "Use PostgreSQL for durable production state", "architect"));
        store.put(MemoryDraft.shared("session", "session-summary:s1", "Session s1 completed database schema migration", "PM"));

        MemoryContextProvider provider = new MemoryContextProvider(store,
                new MemoryConfig(true, null, "project", 300, 5, 0));
        String augmented = provider.augment("Which database should this service use and what was done in prior session?");

        assertThat(augmented).contains("Relevant shared project memory", "PostgreSQL", "database schema migration", "Current task:");
        assertThat(augmented.length()).isLessThan(600);
    }
}
