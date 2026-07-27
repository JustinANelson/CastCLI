package dev.justnels.castcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteMemoryStoreTest {
    @TempDir Path tempDir;

    @Test
    void supportsNamespacedHybridSearchDeduplicationAndOptimisticUpdates() {
        SqliteMemoryStore store = new SqliteMemoryStore(tempDir.resolve("memory.db"));
        MemoryDraft draft = MemoryDraft.shared("project-a", "authentication", "Mobile clients use OAuth PKCE", "dev");
        MemoryEntry created = store.put(draft);

        assertThat(store.put(draft).id()).isEqualTo(created.id());
        assertThat(store.search(MemoryQuery.inNamespace("mobile authentication", "project-a", 5)))
                .extracting(MemoryEntry::id).containsExactly(created.id());
        assertThat(store.search(MemoryQuery.inNamespace("mobile authentication", "project-b", 5))).isEmpty();

        MemoryEntry updated = store.update(created.id(), 1,
                MemoryDraft.shared("project-a", "authentication", "Mobile clients use OAuth2 PKCE", "dev"));
        assertThat(updated.version()).isEqualTo(2);
        assertThatThrownBy(() -> store.update(created.id(), 1, draft)).hasMessageContaining("version conflict");
        assertThat(store.delete(created.id(), 2)).isTrue();
    }

    @Test
    void rejectsSecretsHonorsReadOnlyAndPurgesExpiry() {
        SqliteMemoryStore store = new SqliteMemoryStore(tempDir.resolve("memory.db"));
        assertThatThrownBy(() -> store.put(MemoryDraft.shared("project", "credential", "api_key=super-secret-value", "dev")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credential");

        MemoryEntry locked = store.put(new MemoryDraft("project", "shared", "policy", "Never publish secrets", "dev",
                "test", List.of("security"), 1, 1, null, true, null));
        assertThat(store.delete(locked.id(), locked.version())).isFalse();
        assertThatThrownBy(() -> store.update(locked.id(), locked.version(),
                MemoryDraft.shared("project", "policy", "changed", "dev"))).hasMessageContaining("read-only");

        store.put(new MemoryDraft("project", "session", "temporary", "discard me", "dev", "test", List.of(),
                0.5, 0.5, Instant.now().minusSeconds(1), false, null));
        assertThat(store.purgeExpired()).isEqualTo(1);
    }

    @Test
    void concurrentStoreInstancesDoNotLoseWrites() throws Exception {
        Path database = tempDir.resolve("memory.db");
        SqliteMemoryStore first = new SqliteMemoryStore(database);
        SqliteMemoryStore second = new SqliteMemoryStore(database);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 40; i++) {
                int value = i;
                executor.submit(() -> (value % 2 == 0 ? first : second).put(
                        MemoryDraft.shared("project", "item-" + value, "content-" + value, "test")));
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(first.list("project", 100)).hasSize(40);
    }
}
