package dev.justnels.castcli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachingMemoryStoreTest {

    /** Counts delegate calls so tests can assert the cache actually avoided a round trip. */
    private static final class CountingStore implements MemoryStore {
        private final MemoryStore delegate = new InMemoryMemoryStore();
        final AtomicInteger searches = new AtomicInteger();
        boolean closed;

        @Override public MemoryEntry put(MemoryDraft draft) { return delegate.put(draft); }
        @Override public MemoryEntry update(String id, int expectedVersion, MemoryDraft replacement) {
            return delegate.update(id, expectedVersion, replacement);
        }
        @Override public Optional<MemoryEntry> get(String id) { return delegate.get(id); }
        @Override public List<MemoryEntry> search(MemoryQuery query) { searches.incrementAndGet(); return delegate.search(query); }
        @Override public List<MemoryEntry> list(String namespace, int limit) { return search(MemoryQuery.inNamespace("", namespace, limit)); }
        @Override public boolean delete(String id, int expectedVersion) { return delegate.delete(id, expectedVersion); }
        @Override public int purgeExpired() { return delegate.purgeExpired(); }
        @Override public int purgeOlderThan(int retentionDays) { return delegate.purgeOlderThan(retentionDays); }
        @Override public void close() { closed = true; }
    }

    @Test
    void repeatedIdenticalSearchHitsDelegateOnce() {
        CountingStore counting = new CountingStore();
        CachingMemoryStore cache = new CachingMemoryStore(counting);
        cache.put(MemoryDraft.shared("project", "topic", "content", "Agent"));

        MemoryQuery query = MemoryQuery.inNamespace("content", "project", 10);
        List<MemoryEntry> first = cache.search(query);
        List<MemoryEntry> second = cache.search(query);

        assertThat(first).hasSize(1);
        assertThat(second).isEqualTo(first);
        assertThat(counting.searches.get()).isEqualTo(1);
        assertThat(cache.stats().hits()).isEqualTo(1);
        assertThat(cache.stats().misses()).isEqualTo(1);
    }

    @Test
    void writeInvalidatesCachedSearchResults() {
        CountingStore counting = new CountingStore();
        CachingMemoryStore cache = new CachingMemoryStore(counting);
        cache.put(MemoryDraft.shared("project", "topic-a", "first entry", "Agent"));

        MemoryQuery query = MemoryQuery.inNamespace("", "project", 10);
        assertThat(cache.search(query)).hasSize(1);
        assertThat(counting.searches.get()).isEqualTo(1);

        cache.put(MemoryDraft.shared("project", "topic-b", "second entry", "Agent"));
        assertThat(cache.search(query)).hasSize(2);
        assertThat(counting.searches.get()).isEqualTo(2);
    }

    @Test
    void differentQueriesAreCachedIndependently() {
        CountingStore counting = new CountingStore();
        CachingMemoryStore cache = new CachingMemoryStore(counting);
        cache.put(MemoryDraft.shared("project", "topic", "content", "Agent"));
        cache.put(MemoryDraft.shared("session", "topic", "content", "Agent"));

        cache.list("project", 10);
        cache.list("session", 10);
        cache.list("project", 10);

        assertThat(counting.searches.get()).isEqualTo(2);
    }

    @Test
    void closeDelegatesToUnderlyingStore() {
        CountingStore counting = new CountingStore();
        new CachingMemoryStore(counting).close();
        assertThat(counting.closed).isTrue();
    }
}
