package dev.justnels.castcli.tools;

import dev.justnels.castcli.memory.MemoryDraft;
import dev.justnels.castcli.memory.MemoryEntry;
import dev.justnels.castcli.memory.MemoryQuery;
import dev.justnels.castcli.memory.MemoryStore;
import dev.justnels.castcli.memory.SqliteMemoryStore;
import dev.justnels.castcli.observability.CastTelemetry;
import io.opentelemetry.api.common.Attributes;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.util.List;

/** Agent tools backed by the transactional shared memory store. */
public final class MemoryTools {
    private final MemoryStore store;
    private final String defaultNamespace;

    public MemoryTools(Path workspaceRoot) {
        this(new SqliteMemoryStore(workspaceRoot.toAbsolutePath().normalize()
                .resolve(".cast").resolve("memory").resolve("memory.db")), "project");
    }

    public MemoryTools(MemoryStore store, String defaultNamespace) {
        this.store = store;
        this.defaultNamespace = defaultNamespace == null || defaultNamespace.isBlank() ? "project" : defaultNamespace;
    }

    @Tool("Persist a reusable decision, preference, finding, or architectural fact in shared project memory. Never store credentials.")
    public String rememberContext(String topic, String insight, String author) {
        try (var span = memorySpan("remember")) {
        MemoryEntry entry = store.put(MemoryDraft.shared(defaultNamespace, topic, insight, author));
        span.attribute("castcli.memory.id", entry.id()).attribute("castcli.memory.version", entry.version());
        return "Memory recorded successfully under topic '" + entry.topic() + "' as "
                + entry.id() + " version " + entry.version() + ".";
        }
    }

    @Tool("Recall relevant shared project memories using hybrid keyword and vector search.")
    public String recallContext(String topicQuery, int maxResults) {
        try (var span = memorySpan("recall")) {
        span.attribute("castcli.memory.query.sha256", CastTelemetry.current().promptHash(topicQuery));
        List<MemoryEntry> matches = store.search(MemoryQuery.inNamespace(topicQuery, defaultNamespace,
                maxResults <= 0 ? 10 : maxResults));
        span.attribute("castcli.memory.results", matches.size());
        if (matches.isEmpty()) return "No memories matching '" + topicQuery + "' found.";
        return matches.stream().map(MemoryTools::format).collect(java.util.stream.Collectors.joining("\n---\n"));
        }
    }

    @Tool("Update an existing writable memory using its ID and current version; rejects concurrent stale edits.")
    public String updateContext(String id, int expectedVersion, String topic, String insight, String author) {
        try (var span = memorySpan("update").attribute("castcli.memory.id", id)) {
        MemoryEntry entry = store.update(id, expectedVersion,
                MemoryDraft.shared(defaultNamespace, topic, insight, author));
        span.attribute("castcli.memory.version", entry.version());
        return "Memory " + entry.id() + " updated to version " + entry.version() + ".";
        }
    }

    @Tool("Delete an existing writable memory using its ID and current version.")
    public String forgetContext(String id, int expectedVersion) {
        try (var span = memorySpan("forget").attribute("castcli.memory.id", id)) {
            boolean deleted = store.delete(id, expectedVersion);
            span.attribute("castcli.memory.deleted", deleted);
            return deleted ? "Memory deleted." : "Memory was not deleted; check ID, version, and read-only status.";
        }
    }

    private CastTelemetry.SpanScope memorySpan(String operation) {
        CastTelemetry.current().memoryOperation(Attributes.builder()
                .put("castcli.memory.operation", operation)
                .put("castcli.memory.namespace", defaultNamespace).build());
        return CastTelemetry.current().span("castcli.memory." + operation)
                .attribute("castcli.memory.operation", operation)
                .attribute("castcli.memory.namespace", defaultNamespace);
    }

    private static String format(MemoryEntry entry) {
        return "[" + entry.id() + " v" + entry.version() + "] [" + entry.namespace() + "/" + entry.scope() + "] "
                + entry.topic() + " (by " + entry.author() + ", confidence "
                + String.format(java.util.Locale.ROOT, "%.2f", entry.confidence()) + ")\n" + entry.content();
    }
}
