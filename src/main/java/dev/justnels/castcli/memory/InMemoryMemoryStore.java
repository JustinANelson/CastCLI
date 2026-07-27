package dev.justnels.castcli.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Deterministic test/embedding backend implementing the same version and policy contract as SQLite. */
public final class InMemoryMemoryStore implements MemoryStore {
    private final Map<String, MemoryEntry> entries = new LinkedHashMap<>();

    @Override public synchronized MemoryEntry put(MemoryDraft draft) {
        MemorySecurity.rejectSecrets(draft.content());
        Optional<MemoryEntry> duplicate = entries.values().stream().filter(entry -> entry.namespace().equals(draft.namespace())
                && entry.scope().equals(draft.scope()) && entry.topic().equals(draft.topic().trim())
                && entry.content().equals(draft.content().trim())).findFirst();
        if (duplicate.isPresent()) return duplicate.get();
        Instant now = Instant.now();
        MemoryEntry entry = fromDraft(UUID.randomUUID().toString(), draft, now, now, 1);
        entries.put(entry.id(), entry);
        return entry;
    }

    @Override public synchronized MemoryEntry update(String id, int expectedVersion, MemoryDraft replacement) {
        MemorySecurity.rejectSecrets(replacement.content());
        MemoryEntry current = entries.get(id);
        if (current == null) throw new IllegalArgumentException("Unknown memory: " + id);
        if (current.readOnly()) throw new IllegalStateException("Memory " + id + " is read-only");
        if (current.version() != expectedVersion) throw new IllegalStateException("Memory version conflict: expected "
                + expectedVersion + " but was " + current.version());
        MemoryEntry updated = fromDraft(id, replacement, current.createdAt(), Instant.now(), expectedVersion + 1);
        entries.put(id, updated);
        return updated;
    }

    @Override public synchronized Optional<MemoryEntry> get(String id) { return Optional.ofNullable(entries.get(id)); }

    @Override public synchronized List<MemoryEntry> search(MemoryQuery query) {
        String normalized = query.text().toLowerCase(Locale.ROOT);
        return entries.values().stream()
                .filter(entry -> entry.expiresAt() == null || entry.expiresAt().isAfter(Instant.now()))
                .filter(entry -> query.namespaces().isEmpty() || query.namespaces().contains(entry.namespace()))
                .filter(entry -> query.scope() == null || query.scope().isBlank() || query.scope().equals(entry.scope()))
                .filter(entry -> query.tags().isEmpty() || entry.tags().containsAll(query.tags()))
                .filter(entry -> normalized.isBlank() || (entry.topic() + " " + entry.content() + " " + entry.tags())
                        .toLowerCase(Locale.ROOT).contains(normalized)
                        || java.util.Arrays.stream(normalized.split("\\s+")).anyMatch(term -> entry.content().toLowerCase(Locale.ROOT).contains(term)))
                .sorted(Comparator.comparing(MemoryEntry::updatedAt).reversed())
                .limit(query.limit()).toList();
    }

    @Override public synchronized List<MemoryEntry> list(String namespace, int limit) {
        return search(new MemoryQuery("", namespace == null ? List.of() : List.of(namespace), null, List.of(), limit));
    }

    @Override public synchronized boolean delete(String id, int expectedVersion) {
        MemoryEntry current = entries.get(id);
        if (current == null || current.readOnly() || current.version() != expectedVersion) return false;
        entries.remove(id); return true;
    }

    @Override public synchronized int purgeExpired() {
        List<String> ids = entries.values().stream().filter(entry -> entry.expiresAt() != null && !entry.expiresAt().isAfter(Instant.now()))
                .map(MemoryEntry::id).toList();
        ids.forEach(entries::remove); return ids.size();
    }

    @Override public synchronized int purgeOlderThan(int retentionDays) {
        if (retentionDays <= 0) return 0;
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86_400L);
        List<String> ids = entries.values().stream().filter(entry -> !entry.readOnly() && entry.updatedAt().isBefore(cutoff))
                .map(MemoryEntry::id).toList();
        ids.forEach(entries::remove); return ids.size();
    }

    private static MemoryEntry fromDraft(String id, MemoryDraft draft, Instant createdAt, Instant updatedAt, int version) {
        return new MemoryEntry(id, draft.namespace(), draft.scope(), draft.topic().trim(), draft.content().trim(),
                draft.author(), draft.source(), draft.tags(), draft.importance(), draft.confidence(), createdAt, updatedAt,
                draft.expiresAt(), version, draft.readOnly(), draft.supersedesId());
    }
}
