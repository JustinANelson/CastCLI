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
        MemoryContextProvider provider = new MemoryContextProvider(store,
                new MemoryConfig(true, null, "project", 300, 5, 0));
        String augmented = provider.augment("Which database should this service use?");
        assertThat(augmented).contains("Relevant shared project memory", "PostgreSQL", "Current task:");
        assertThat(augmented.length()).isLessThan(500);
    }
}
