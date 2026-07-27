package dev.justnels.castcli.memory;

import java.time.Instant;
import java.util.List;

/** Data accepted when creating or replacing a memory. */
public record MemoryDraft(
        String namespace,
        String scope,
        String topic,
        String content,
        String author,
        String source,
        List<String> tags,
        double importance,
        double confidence,
        Instant expiresAt,
        boolean readOnly,
        String supersedesId) {

    public MemoryDraft {
        namespace = textOrDefault(namespace, "project");
        scope = textOrDefault(scope, "shared");
        requireText(topic, "topic");
        requireText(content, "content");
        author = textOrDefault(author, "Agent");
        source = textOrDefault(source, "agent");
        tags = tags == null ? List.of() : tags.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toList();
        importance = clamp(importance);
        confidence = clamp(confidence);
    }

    public static MemoryDraft shared(String namespace, String topic, String content, String author) {
        return new MemoryDraft(namespace, "shared", topic, content, author, "agent", List.of(),
                0.5, 0.8, null, false, null);
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static String textOrDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }
}
