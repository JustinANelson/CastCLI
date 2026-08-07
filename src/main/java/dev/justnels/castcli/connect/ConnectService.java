package dev.justnels.castcli.connect;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service managing automatic client integration discovery, connectivity testing,
 * and configuration file generation for external coding tools.
 */
public final class ConnectService {

    private final Map<String, ClientConnector> connectors = new LinkedHashMap<>();

    public ConnectService() {
        register(new ClaudeConnector());
        register(new CodexConnector());
        register(new CursorConnector());
        register(new ContinueConnector());
        register(new AiderConnector());
        AntigravityConnector agy = new AntigravityConnector();
        register(agy);
        registerAlias("agy", agy);
    }

    public void register(ClientConnector connector) {
        connectors.put(connector.id().toLowerCase(java.util.Locale.ROOT), connector);
    }

    public void registerAlias(String alias, ClientConnector connector) {
        connectors.put(alias.toLowerCase(java.util.Locale.ROOT), connector);
    }

    public List<ClientConnector> listConnectors() {
        return connectors.values().stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    public ClientConnector getConnector(String id) {
        if (id == null) {
            return null;
        }
        return connectors.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public ClientConnector.ConnectResult connectClient(String clientId, Path workspaceRoot, int gatewayPort,
                                                      String bearerToken, boolean dryRun, boolean force) {
        ClientConnector connector = getConnector(clientId);
        if (connector == null) {
            throw new IllegalArgumentException("Unknown client '" + clientId + "'. Supported clients: " + getSupportedClientIds() + ", all, all-connected");
        }
        Path configPath = connector.resolveConfigPath(workspaceRoot);
        return connector.connect(configPath, gatewayPort, bearerToken, dryRun, force);
    }

    public List<ClientConnector.ConnectResult> connectClientOrAll(String clientId, Path workspaceRoot, int gatewayPort,
                                                                 String bearerToken, boolean dryRun, boolean force) {
        if ("all".equalsIgnoreCase(clientId)) {
            return connectAll(workspaceRoot, gatewayPort, bearerToken, dryRun, force, false);
        }
        if ("all-connected".equalsIgnoreCase(clientId)) {
            return connectAll(workspaceRoot, gatewayPort, bearerToken, dryRun, force, true);
        }
        return List.of(connectClient(clientId, workspaceRoot, gatewayPort, bearerToken, dryRun, force));
    }

    public ClientConnector.DisconnectResult disconnectClient(String clientId, Path workspaceRoot, boolean dryRun) {
        ClientConnector connector = getConnector(clientId);
        if (connector == null) {
            throw new IllegalArgumentException("Unknown client '" + clientId + "'. Supported clients: " + getSupportedClientIds() + ", all, all-connected");
        }
        Path configPath = connector.resolveConfigPath(workspaceRoot);
        return connector.disconnect(configPath, dryRun);
    }

    public List<ClientConnector.DisconnectResult> disconnectClientOrAll(String clientId, Path workspaceRoot, boolean dryRun) {
        if ("all".equalsIgnoreCase(clientId) || "all-connected".equalsIgnoreCase(clientId)) {
            return disconnectAll(workspaceRoot, dryRun, "all-connected".equalsIgnoreCase(clientId));
        }
        return List.of(disconnectClient(clientId, workspaceRoot, dryRun));
    }

    public List<ClientConnector.ConnectResult> connectAll(Path workspaceRoot, int gatewayPort, String bearerToken,
                                                          boolean dryRun, boolean force, boolean onlyConnected) {
        List<ClientConnector.ConnectResult> results = new java.util.ArrayList<>();
        for (ClientConnector connector : listConnectors()) {
            Path configPath = connector.resolveConfigPath(workspaceRoot);
            if (!onlyConnected || connector.isConnected(configPath)) {
                results.add(connector.connect(configPath, gatewayPort, bearerToken, dryRun, force));
            }
        }
        return results;
    }

    public List<ClientConnector.DisconnectResult> disconnectAll(Path workspaceRoot, boolean dryRun, boolean onlyConnected) {
        List<ClientConnector.DisconnectResult> results = new java.util.ArrayList<>();
        for (ClientConnector connector : listConnectors()) {
            Path configPath = connector.resolveConfigPath(workspaceRoot);
            if (!onlyConnected || connector.isConnected(configPath)) {
                results.add(connector.disconnect(configPath, dryRun));
            }
        }
        return results;
    }

    public List<ClientConnector.ConnectResult> refreshAll(Path workspaceRoot, int gatewayPort, String bearerToken, boolean dryRun) {
        return connectAll(workspaceRoot, gatewayPort, bearerToken, dryRun, true, true);
    }

    public boolean checkConnectivity(int gatewayPort, String bearerToken) {
        String url = "http://127.0.0.1:" + gatewayPort + "/v1/models";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(3));
            if (bearerToken != null && !bearerToken.isBlank()) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public String getSupportedClientIds() {
        return String.join(", ", connectors.keySet());
    }
}
