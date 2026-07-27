package dev.justnels.castcli.config;

/** Durable MCP invocation auditing and user-visible delegation receipts. */
public record McpAuditConfig(
        boolean enabled,
        String path,
        boolean includeResponseMetadata) {

    public McpAuditConfig {
        path = path == null || path.isBlank() ? ".cast/metrics/mcp-usage.jsonl" : path;
    }

    public static McpAuditConfig defaults() {
        return new McpAuditConfig(true, null, true);
    }
}
