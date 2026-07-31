package dev.justnels.castcli.connect;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CursorConnector implements ClientConnector {

    @Override
    public String id() {
        return "cursor";
    }

    @Override
    public String description() {
        return "Cursor IDE (MCP stdio server registration in .cursor/mcp.json)";
    }

    @Override
    public Path resolveConfigPath(Path workspaceRoot) {
        if (workspaceRoot != null) {
            return workspaceRoot.resolve(".cursor").resolve("mcp.json");
        }
        Path userHome = Path.of(System.getProperty("user.home"));
        Path globalConfig = userHome.resolve(".cursor").resolve("mcp.json");
        if (Files.isRegularFile(globalConfig)) {
            return globalConfig;
        }
        return globalConfig;
    }

    @Override
    public boolean isConnected(Path configPath) {
        ObjectNode root = JsonConfigUtils.parseOrCreateObject(configPath);
        return JsonConfigUtils.hasMcpServer(root, "cast-cli");
    }

    @Override
    public String generateConfigContent(Path configPath, int gatewayPort, String bearerToken) {
        ObjectNode root = JsonConfigUtils.parseOrCreateObject(configPath);
        JsonConfigUtils.updateMcpServer(root, "cast-cli", "cast-cli", List.of("mcp-serve"));
        return JsonConfigUtils.toPrettyString(root);
    }

    @Override
    public String generateDiff(Path configPath, String newContent) {
        return JsonConfigUtils.buildDiff(configPath, newContent);
    }

    @Override
    public ConnectResult connect(Path configPath, int gatewayPort, String bearerToken, boolean dryRun, boolean force) {
        if (!force && isConnected(configPath)) {
            return new ConnectResult(true, false, configPath, "", "CastCLI is already registered in " + configPath);
        }
        String newContent = generateConfigContent(configPath, gatewayPort, bearerToken);
        String diff = generateDiff(configPath, newContent);

        if (dryRun) {
            return new ConnectResult(true, true, configPath, diff, "[Dry-run] Proposing configuration updates to " + configPath);
        }

        try {
            if (configPath.getParent() != null && !Files.isDirectory(configPath.getParent())) {
                Files.createDirectories(configPath.getParent());
            }
            JsonConfigUtils.createBackup(configPath);
            Files.writeString(configPath, newContent);
            return new ConnectResult(true, true, configPath, diff, "Successfully connected CastCLI to " + configPath);
        } catch (IOException e) {
            return new ConnectResult(false, false, configPath, "", "Failed to update " + configPath + ": " + e.getMessage());
        }
    }

    @Override
    public DisconnectResult disconnect(Path configPath, boolean dryRun) {
        if (!Files.isRegularFile(configPath)) {
            return new DisconnectResult(true, false, configPath, "", "Config file " + configPath + " does not exist.");
        }
        ObjectNode root = JsonConfigUtils.parseOrCreateObject(configPath);
        boolean removed = JsonConfigUtils.removeMcpServer(root, "cast-cli");
        if (!removed) {
            return new DisconnectResult(true, false, configPath, "", "CastCLI is not registered in " + configPath);
        }
        String newContent = JsonConfigUtils.toPrettyString(root);
        String diff = generateDiff(configPath, newContent);

        if (dryRun) {
            return new DisconnectResult(true, true, configPath, diff, "[Dry-run] Proposing removal of CastCLI from " + configPath);
        }

        try {
            JsonConfigUtils.createBackup(configPath);
            Files.writeString(configPath, newContent);
            return new DisconnectResult(true, true, configPath, diff, "Successfully disconnected CastCLI from " + configPath);
        } catch (IOException e) {
            return new DisconnectResult(false, false, configPath, "", "Failed to update " + configPath + ": " + e.getMessage());
        }
    }
}
