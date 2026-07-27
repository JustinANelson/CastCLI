package dev.justnels.castcli.memory;

import java.util.List;

/** Namespaced hybrid search request with optional tenant isolation. */
public record MemoryQuery(String text, List<String> namespaces, String scope, List<String> tags, int limit, String tenantId) {

    public MemoryQuery {
        text = text == null ? "" : text.trim();
        namespaces = namespaces == null ? List.of() : List.copyOf(namespaces);
        tags = tags == null ? List.of() : List.copyOf(tags);
        tenantId = tenantId == null ? "" : tenantId.trim();
        if (limit < 1) limit = 10;
        if (limit > 100) limit = 100;
    }

    public MemoryQuery(String text, List<String> namespaces, String scope, List<String> tags, int limit) {
        this(text, namespaces, scope, tags, limit, null);
    }

    public static MemoryQuery inNamespace(String text, String namespace, int limit) {
        return new MemoryQuery(text, List.of(namespace), null, List.of(), limit, null);
    }

    public static MemoryQuery forTenant(String text, String namespace, String tenantId, int limit) {
        return new MemoryQuery(text, List.of(namespace), null, List.of(), limit, tenantId);
    }
}
