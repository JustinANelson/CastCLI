package dev.justnels.castcli.connect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AiderConnector implements ClientConnector {

    @Override
    public String id() {
        return "aider";
    }

    @Override
    public String description() {
        return "Aider CLI (gateway endpoint configuration in .aider.conf.yml)";
    }

    @Override
    public Path resolveConfigPath(Path workspaceRoot) {
        if (workspaceRoot != null) {
            return workspaceRoot.resolve(".aider.conf.yml");
        }
        Path userHome = Path.of(System.getProperty("user.home"));
        Path globalConfig = userHome.resolve(".aider.conf.yml");
        if (Files.isRegularFile(globalConfig)) {
            return globalConfig;
        }
        return globalConfig;
    }

    @Override
    public boolean isConnected(Path configPath) {
        if (!Files.isRegularFile(configPath)) {
            return false;
        }
        try {
            String content = Files.readString(configPath);
            return content.contains("openai-api-base:") || content.contains("cast-cli");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String generateConfigContent(Path configPath, int gatewayPort, String bearerToken) {
        StringBuilder sb = new StringBuilder();
        if (Files.isRegularFile(configPath)) {
            try {
                String existing = Files.readString(configPath);
                for (String line : existing.split("\\r?\\n")) {
                    if (!line.startsWith("openai-api-base:")) {
                        sb.append(line).append("\n");
                    }
                }
            } catch (IOException ignored) {
            }
        }
        sb.append("# Added by CastCLI connect\n");
        sb.append("openai-api-base: http://127.0.0.1:").append(gatewayPort).append("/v1\n");
        return sb.toString();
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
        try {
            String content = Files.readString(configPath);
            if (!content.contains("openai-api-base:")) {
                return new DisconnectResult(true, false, configPath, "", "CastCLI is not registered in " + configPath);
            }
            StringBuilder sb = new StringBuilder();
            for (String line : content.split("\\r?\\n")) {
                if (!line.startsWith("openai-api-base:") && !line.contains("Added by CastCLI connect")) {
                    sb.append(line).append("\n");
                }
            }
            String newContent = sb.toString();
            String diff = generateDiff(configPath, newContent);

            if (dryRun) {
                return new DisconnectResult(true, true, configPath, diff, "[Dry-run] Proposing removal of CastCLI from " + configPath);
            }

            JsonConfigUtils.createBackup(configPath);
            Files.writeString(configPath, newContent);
            return new DisconnectResult(true, true, configPath, diff, "Successfully disconnected CastCLI from " + configPath);
        } catch (IOException e) {
            return new DisconnectResult(false, false, configPath, "", "Failed to update " + configPath + ": " + e.getMessage());
        }
    }
}
