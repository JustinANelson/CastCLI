package dev.justnels.castcli.connect;

import java.nio.file.Path;

/**
 * Contract for client integration connectors managed by {@link ConnectService}.
 */
public interface ClientConnector {

    /**
     * Unique identifier for the client (e.g. "claude", "codex", "cursor", "continue", "aider").
     */
    String id();

    /**
     * Human-readable description of the client connector.
     */
    String description();

    /**
     * Resolves the primary configuration file path for this client in the given workspace.
     */
    Path resolveConfigPath(Path workspaceRoot);

    /**
     * Checks if CastCLI is already connected in the given configuration file.
     */
    boolean isConnected(Path configPath);

    /**
     * Generates the updated configuration file content incorporating CastCLI settings.
     */
    String generateConfigContent(Path configPath, int gatewayPort, String bearerToken);

    /**
     * Generates a unified-style line diff showing proposed configuration changes.
     */
    String generateDiff(Path configPath, String newContent);

    /**
     * Connects CastCLI to the target client configuration file.
     */
    ConnectResult connect(Path configPath, int gatewayPort, String bearerToken, boolean dryRun, boolean force);

    /**
     * Reverts CastCLI settings from the target client configuration file.
     */
    DisconnectResult disconnect(Path configPath, boolean dryRun);

    record ConnectResult(boolean success, boolean modified, Path configPath, String diff, String message) {}

    record DisconnectResult(boolean success, boolean modified, Path configPath, String diff, String message) {}
}
