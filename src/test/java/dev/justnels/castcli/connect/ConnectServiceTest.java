package dev.justnels.castcli.connect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectServiceTest {

    @Test
    void listsAllSupportedConnectors() {
        ConnectService service = new ConnectService();
        List<ClientConnector> connectors = service.listConnectors();

        assertFalse(connectors.isEmpty());
        assertTrue(connectors.stream().anyMatch(c -> c.id().equals("claude")));
        assertTrue(connectors.stream().anyMatch(c -> c.id().equals("cursor")));
        assertTrue(connectors.stream().anyMatch(c -> c.id().equals("codex")));
        assertTrue(connectors.stream().anyMatch(c -> c.id().equals("continue")));
        assertTrue(connectors.stream().anyMatch(c -> c.id().equals("aider")));
        assertTrue(connectors.stream().anyMatch(c -> c.id().equals("antigravity")));
    }

    @Test
    void claudeConnectorGeneratesValidMcpConfig(@TempDir Path tempDir) throws IOException {
        ConnectService service = new ConnectService();
        Path claudeConfig = tempDir.resolve(".claude.json");

        ClientConnector.ConnectResult result = service.connectClient("claude", tempDir, 8081, null, false, false);
        assertTrue(result.success());
        assertTrue(result.modified());
        assertTrue(Files.isRegularFile(claudeConfig));

        String content = Files.readString(claudeConfig);
        assertTrue(content.contains("cast-cli"));
        assertTrue(content.contains("mcp-serve"));

        // Idempotent second connect without force
        ClientConnector.ConnectResult second = service.connectClient("claude", tempDir, 8081, null, false, false);
        assertTrue(second.success());
        assertFalse(second.modified());

        // Second connect with force
        ClientConnector.ConnectResult forced = service.connectClient("claude", tempDir, 8081, null, false, true);
        assertTrue(forced.success());
        assertTrue(forced.modified());

        // Disconnect dry run
        ClientConnector.DisconnectResult dryDisconnect = service.disconnectClient("claude", tempDir, true);
        assertTrue(dryDisconnect.success());
        assertTrue(dryDisconnect.modified());

        // Actual disconnect
        ClientConnector.DisconnectResult disconnect = service.disconnectClient("claude", tempDir, false);
        assertTrue(disconnect.success());
        assertTrue(disconnect.modified());
    }

    @Test
    void continueConnectorWorkflow(@TempDir Path tempDir) throws IOException {
        ConnectService service = new ConnectService();
        Path continueConfig = tempDir.resolve(".continue").resolve("config.json");

        ClientConnector.ConnectResult result = service.connectClient("continue", tempDir, 8081, "secret", false, false);
        assertTrue(result.success());
        assertTrue(result.modified());
        assertTrue(Files.isRegularFile(continueConfig));

        ClientConnector.DisconnectResult disconnectResult = service.disconnectClient("continue", tempDir, false);
        assertTrue(disconnectResult.success());
        assertTrue(disconnectResult.modified());
    }

    @Test
    void aiderConnectorWorkflow(@TempDir Path tempDir) throws IOException {
        ConnectService service = new ConnectService();
        Path aiderConfig = tempDir.resolve(".aider.conf.yml");

        ClientConnector.ConnectResult result = service.connectClient("aider", tempDir, 8081, null, false, false);
        assertTrue(result.success());
        assertTrue(result.modified());
        assertTrue(Files.isRegularFile(aiderConfig));
        assertTrue(Files.readString(aiderConfig).contains("openai-api-base: http://127.0.0.1:8081/v1"));

        ClientConnector.DisconnectResult disconnectResult = service.disconnectClient("aider", tempDir, false);
        assertTrue(disconnectResult.success());
        assertTrue(disconnectResult.modified());
        assertFalse(Files.readString(aiderConfig).contains("openai-api-base"));
    }

    @Test
    void antigravityConnectorWorkflow(@TempDir Path tempDir) throws IOException {
        ConnectService service = new ConnectService();
        Path agyConfig = tempDir.resolve(".agents").resolve("mcp.json");

        // Test connecting with alias 'agy'
        ClientConnector.ConnectResult result = service.connectClient("agy", tempDir, 8081, null, false, false);
        assertTrue(result.success());
        assertTrue(result.modified());
        assertTrue(Files.isRegularFile(agyConfig));
        String content = Files.readString(agyConfig);
        assertTrue(content.contains("cast-cli"));
        assertTrue(content.contains("mcp-serve"));

        // Test disconnecting with 'antigravity'
        ClientConnector.DisconnectResult disconnectResult = service.disconnectClient("antigravity", tempDir, false);
        assertTrue(disconnectResult.success());
        assertTrue(disconnectResult.modified());
        assertFalse(Files.readString(agyConfig).contains("cast-cli"));
    }

    @Test
    void dryRunDoesNotModifyFile(@TempDir Path tempDir) {
        ConnectService service = new ConnectService();
        Path cursorConfig = tempDir.resolve(".cursor").resolve("mcp.json");

        ClientConnector.ConnectResult result = service.connectClient("cursor", tempDir, 8081, null, true, false);
        assertTrue(result.success());
        assertTrue(result.modified());
        assertFalse(Files.exists(cursorConfig));
        assertFalse(result.diff().isEmpty());
    }

    @Test
    void disconnectRemovesCastCliConfig(@TempDir Path tempDir) throws IOException {
        ConnectService service = new ConnectService();

        // First connect
        service.connectClient("codex", tempDir, 8081, null, false, false);
        Path configPath = tempDir.resolve(".codex").resolve("config.json");
        assertTrue(Files.isRegularFile(configPath));
        assertTrue(Files.readString(configPath).contains("cast-cli"));

        // Now disconnect
        ClientConnector.DisconnectResult disconnectResult = service.disconnectClient("codex", tempDir, false);
        assertTrue(disconnectResult.success());
        assertTrue(disconnectResult.modified());
        assertFalse(Files.readString(configPath).contains("cast-cli"));

        // Verify backup was created
        Path backupPath = tempDir.resolve(".codex").resolve("config.json.bak");
        assertTrue(Files.isRegularFile(backupPath));
    }

    @Test
    void disconnectNonExistentFileReturnsSuccess(@TempDir Path tempDir) {
        ConnectService service = new ConnectService();
        ClientConnector.DisconnectResult result = service.disconnectClient("cursor", tempDir, false);
        assertTrue(result.success());
        assertFalse(result.modified());
    }

    @Test
    void throwsOnUnknownClient(@TempDir Path tempDir) {
        ConnectService service = new ConnectService();
        assertThrows(IllegalArgumentException.class, () ->
                service.connectClient("invalid-client", tempDir, 8081, null, false, false));
        assertThrows(IllegalArgumentException.class, () ->
                service.disconnectClient("invalid-client", tempDir, false));
    }
}
