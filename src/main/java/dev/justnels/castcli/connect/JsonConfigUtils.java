package dev.justnels.castcli.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Utilities for reading, updating, backup, and diffing client configuration files.
 */
public final class JsonConfigUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonConfigUtils() {}

    public static ObjectNode parseOrCreateObject(Path path) {
        if (Files.isRegularFile(path)) {
            try {
                JsonNode node = MAPPER.readTree(path.toFile());
                if (node instanceof ObjectNode objectNode) {
                    return objectNode;
                }
            } catch (IOException ignored) {
            }
        }
        return MAPPER.createObjectNode();
    }

    public static String toPrettyString(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            return node.toString();
        }
    }

    public static void updateMcpServer(ObjectNode root, String serverName, String command, List<String> args) {
        ObjectNode mcpServers;
        if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
            mcpServers = (ObjectNode) root.get("mcpServers");
        } else {
            mcpServers = root.putObject("mcpServers");
        }

        ObjectNode serverConfig = mcpServers.putObject(serverName);
        serverConfig.put("command", command);
        ArrayNode argsArray = serverConfig.putArray("args");
        for (String arg : args) {
            argsArray.add(arg);
        }
    }

    public static boolean removeMcpServer(ObjectNode root, String serverName) {
        if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
            ObjectNode mcpServers = (ObjectNode) root.get("mcpServers");
            if (mcpServers.has(serverName)) {
                mcpServers.remove(serverName);
                if (mcpServers.isEmpty()) {
                    root.remove("mcpServers");
                }
                return true;
            }
        }
        return false;
    }

    public static boolean hasMcpServer(ObjectNode root, String serverName) {
        return root.has("mcpServers") &&
                root.get("mcpServers").isObject() &&
                root.get("mcpServers").has(serverName);
    }

    public static Path createBackup(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        Path backup = path.resolveSibling(path.getFileName().toString() + ".bak");
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    public static String buildDiff(Path path, String newContent) {
        String oldContent = "";
        if (Files.isRegularFile(path)) {
            try {
                oldContent = Files.readString(path);
            } catch (IOException ignored) {
            }
        }
        String[] oldLines = oldContent.isEmpty() ? new String[0] : oldContent.split("\\r?\\n");
        String[] newLines = newContent.split("\\r?\\n");

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(path.getFileName()).append(" (current)\n");
        sb.append("+++ ").append(path.getFileName()).append(" (proposed)\n");

        for (String line : oldLines) {
            if (!containsLine(newLines, line)) {
                sb.append("- ").append(line).append("\n");
            }
        }
        for (String line : newLines) {
            if (!containsLine(oldLines, line)) {
                sb.append("+ ").append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static boolean containsLine(String[] lines, String target) {
        for (String l : lines) {
            if (l.trim().equals(target.trim())) {
                return true;
            }
        }
        return false;
    }
}
