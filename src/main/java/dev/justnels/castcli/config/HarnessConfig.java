package dev.justnels.castcli.config;

import java.util.List;

public record HarnessConfig(
        List<ProviderConfig> providers,
        RoutingConfig routing,
        ToolConfig tools,
        List<McpServerConfig> mcpServers,
        EmbeddingConfig embeddings,
        MemoryConfig memory,
        ReliabilityConfig reliability,
        ObservabilityConfig observability,
        McpAuditConfig mcpAudit,
        CommissioningConfig commissioning) {
    public HarnessConfig {
        providers = providers == null ? List.of() : List.copyOf(providers);
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("At least one provider must be configured");
        }
        if (routing == null) {
            routing = new RoutingConfig(240, true);
        }
        if (tools == null) {
            tools = new ToolConfig(".", 262_144, false);
        }
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        embeddings = embeddings == null ? EmbeddingConfig.disabled() : embeddings;
        memory = memory == null ? MemoryConfig.disabled() : memory;
        reliability = reliability == null ? ReliabilityConfig.defaults() : reliability;
        observability = observability == null ? ObservabilityConfig.disabled() : observability;
        mcpAudit = mcpAudit == null ? McpAuditConfig.defaults() : mcpAudit;
        commissioning = commissioning == null ? CommissioningConfig.automatic() : commissioning;
    }

    public HarnessConfig(List<ProviderConfig> providers, RoutingConfig routing, ToolConfig tools,
                         List<McpServerConfig> mcpServers, EmbeddingConfig embeddings,
                         MemoryConfig memory, ReliabilityConfig reliability, ObservabilityConfig observability,
                         McpAuditConfig mcpAudit) {
        this(providers, routing, tools, mcpServers, embeddings, memory, reliability, observability, mcpAudit, null);
    }

    public HarnessConfig(List<ProviderConfig> providers, RoutingConfig routing, ToolConfig tools,
                         List<McpServerConfig> mcpServers, EmbeddingConfig embeddings,
                         MemoryConfig memory, ReliabilityConfig reliability, ObservabilityConfig observability) {
        this(providers, routing, tools, mcpServers, embeddings, memory, reliability, observability, null, null);
    }

    public HarnessConfig(List<ProviderConfig> providers, RoutingConfig routing, ToolConfig tools,
                         List<McpServerConfig> mcpServers, EmbeddingConfig embeddings,
                         MemoryConfig memory, ReliabilityConfig reliability) {
        this(providers, routing, tools, mcpServers, embeddings, memory, reliability, null, null, null);
    }

    public HarnessConfig(List<ProviderConfig> providers, RoutingConfig routing, ToolConfig tools,
                         List<McpServerConfig> mcpServers, EmbeddingConfig embeddings) {
        this(providers, routing, tools, mcpServers, embeddings, null, null, null, null, null);
    }

    public HarnessConfig(List<ProviderConfig> providers, RoutingConfig routing, ToolConfig tools, List<McpServerConfig> mcpServers) {
        this(providers, routing, tools, mcpServers, null, null, null, null, null, null);
    }

    public HarnessConfig(List<ProviderConfig> providers, RoutingConfig routing, ToolConfig tools) {
        this(providers, routing, tools, List.of(), null, null, null, null, null, null);
    }
}

