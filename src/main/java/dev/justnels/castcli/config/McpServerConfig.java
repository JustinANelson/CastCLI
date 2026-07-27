package dev.justnels.castcli.config;

import java.util.List;
import java.util.Map;

/**
 * Describes an external MCP server to launch over stdio and consume as a tool source.
 * Only the stdio transport is supported; {@code command} is the executable and {@code args}
 * are passed to it verbatim (no shell interpretation).
 */
public record McpServerConfig(String name, String command, List<String> args, Map<String, String> environment, boolean enabled) {
    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("mcp server name must not be blank");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("mcp server command must not be blank");
        }
        args = args == null ? List.of() : List.copyOf(args);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}

