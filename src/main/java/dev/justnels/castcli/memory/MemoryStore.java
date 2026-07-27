package dev.justnels.castcli.memory;

import java.util.List;
import java.util.Optional;

public interface MemoryStore extends AutoCloseable {
    MemoryEntry put(MemoryDraft draft);
    MemoryEntry update(String id, int expectedVersion, MemoryDraft replacement);
    Optional<MemoryEntry> get(String id);
    List<MemoryEntry> search(MemoryQuery query);
    List<MemoryEntry> list(String namespace, int limit);
    boolean delete(String id, int expectedVersion);
    int purgeExpired();
    int purgeOlderThan(int retentionDays);
    @Override default void close() { }
}
