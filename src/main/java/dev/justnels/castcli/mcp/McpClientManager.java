package dev.justnels.castcli.mcp;

import dev.justnels.castcli.config.McpServerConfig;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Launches the {@code mcpServers} configured in {@link dev.justnels.castcli.config.HarnessConfig} as
 * stdio subprocesses and exposes their tools to routed models via a single {@link ToolProvider}. Each
 * enabled server becomes an additional tool source alongside the harness's own Java tools; this is the
 * harness acting as an MCP *client*, complementing {@link dev.justnels.castcli.mcp.McpStdioServer},
 * which lets the harness act as an MCP *server* for other agents.
 */
public final class McpClientManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final List<McpClient> clients = new ArrayList<>();

    private McpClientManager(List<McpClient> clients) {
        this.clients.addAll(clients);
    }

    public static McpClientManager start(List<McpServerConfig> serverConfigs) {
        List<McpClient> clients = new ArrayList<>();
        for (McpServerConfig serverConfig : serverConfigs) {
            if (!serverConfig.enabled()) {
                continue;
            }
            try {
                clients.add(startClient(serverConfig));
            } catch (Exception e) {
                log.warn("Failed to start MCP server '{}': {}", serverConfig.name(), e.getMessage());
            }
        }
        return new McpClientManager(clients);
    }

    private static McpClient startClient(McpServerConfig serverConfig) {
        List<String> command = new ArrayList<>();
        command.add(serverConfig.command());
        command.addAll(serverConfig.args());

        StdioMcpTransport transport = new StdioMcpTransport.Builder()
                .command(command)
                .environment(serverConfig.environment())
                .logEvents(false)
                .build();

        return new dev.langchain4j.mcp.client.DefaultMcpClient.Builder()
                .key(serverConfig.name())
                .clientName("java-local-llm-harness")
                .transport(transport)
                .build();
    }

    /** Returns null when no MCP servers are configured/enabled, so callers can skip attaching a tool provider. */
    public ToolProvider toolProvider() {
        if (clients.isEmpty()) {
            return null;
        }
        return McpToolProvider.builder().mcpClients(clients).build();
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    @Override
    public void close() {
        for (McpClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Failed to close MCP client: {}", e.getMessage());
            }
        }
    }
}

