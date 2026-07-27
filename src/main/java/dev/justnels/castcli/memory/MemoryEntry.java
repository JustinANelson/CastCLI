package dev.justnels.castcli.memory;

import java.time.Instant;
import java.util.List;

/** Immutable, versioned unit of shared agent memory. */
public record MemoryEntry(
        String id,
        String namespace,
        String scope,
        String topic,
        String content,
        String author,
        String source,
        List<String> tags,
        double importance,
        double confidence,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        int version,
        boolean readOnly,
        String supersedesId) {
    public MemoryEntry {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
