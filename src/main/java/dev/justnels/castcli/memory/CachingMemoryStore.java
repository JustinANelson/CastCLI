package dev.justnels.castcli.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-through LRU cache in front of a durable {@link MemoryStore}. {@link MemoryContextProvider}
 * calls {@code search()} before every single model request, so an uncached SQLite-backed store pays
 * a disk round trip on every prompt; this decorator keeps recent query results resident in RAM.
 * Any write invalidates the whole cache rather than tracking per-namespace dependencies: writes are
 * rare (explicit {@code memory remember}/{@code forget}, session turnover summaries) relative to the
 * read volume, so the simplicity is worth the occasional unnecessary miss.
 */
public final class CachingMemoryStore implements MemoryStore {
    private final MemoryStore delegate;
    private final int maxEntries;
    private final Map<MemoryQuery, List<MemoryEntry>> cache;
    private long hits;
    private long misses;

    public CachingMemoryStore(MemoryStore delegate) {
        this(delegate, 256);
    }

    public CachingMemoryStore(MemoryStore delegate, int maxEntries) {
        this.delegate = delegate;
        this.maxEntries = Math.max(1, maxEntries);
        this.cache = new LinkedHashMap<>(this.maxEntries, 0.75f, true);
    }

    @Override
    public MemoryEntry put(MemoryDraft draft) {
        MemoryEntry entry = delegate.put(draft);
        invalidate();
        return entry;
    }

    @Override
    public MemoryEntry update(String id, int expectedVersion, MemoryDraft replacement) {
        MemoryEntry entry = delegate.update(id, expectedVersion, replacement);
        invalidate();
        return entry;
    }

    @Override
    public Optional<MemoryEntry> get(String id) {
        return delegate.get(id);
    }

    @Override
    public List<MemoryEntry> search(MemoryQuery query) {
        synchronized (cache) {
            List<MemoryEntry> cached = cache.get(query);
            if (cached != null) {
                hits++;
                return cached;
            }
        }
        List<MemoryEntry> result = delegate.search(query);
        synchronized (cache) {
            cache.put(query, result);
            misses++;
            evictToBounds();
        }
        return result;
    }

    @Override
    public List<MemoryEntry> list(String namespace, int limit) {
        return search(new MemoryQuery("", namespace == null ? List.of() : List.of(namespace), null, List.of(), limit));
    }

    @Override
    public boolean delete(String id, int expectedVersion) {
        boolean deleted = delegate.delete(id, expectedVersion);
        if (deleted) invalidate();
        return deleted;
    }

    @Override
    public int purgeExpired() {
        int purged = delegate.purgeExpired();
        if (purged > 0) invalidate();
        return purged;
    }

    @Override
    public int purgeOlderThan(int retentionDays) {
        int purged = delegate.purgeOlderThan(retentionDays);
        if (purged > 0) invalidate();
        return purged;
    }

    @Override
    public void close() {
        delegate.close();
    }

    public Stats stats() {
        synchronized (cache) {
            return new Stats(cache.size(), maxEntries, hits, misses);
        }
    }

    private void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private void evictToBounds() {
        var iterator = cache.entrySet().iterator();
        while (cache.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    public record Stats(int entries, int maxEntries, long hits, long misses) { }
}
