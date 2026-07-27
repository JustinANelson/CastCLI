package dev.justnels.castcli.doctor;

import com.sun.net.httpserver.HttpServer;

import dev.justnels.castcli.config.ConfigLoader;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.doctor.DoctorReport.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DoctorServiceTest {
    private HttpServer ollamaServer;

    @AfterEach
    void stopServer() {
        if (ollamaServer != null) ollamaServer.stop(0);
    }

    @Test
    void diagnosesWorkspaceAndConfigHealth(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "providers": [
                    {
                      "id": "test-provider",
                      "tier": "SMALL_LOCAL",
                      "baseUrl": "http://127.0.0.1:1/v1",
                      "modelName": "qwen2.5-coder",
                      "temperature": 0.0,
                      "timeoutSeconds": 10,
                      "toolsEnabled": false,
                      "enabled": true,
                      "apiKeyEnv": "TEST_KEY_NOT_SET",
                      "costPerMillionInputTokens": 0.0,
                      "costPerMillionOutputTokens": 0.0
                    }
                  ],
                  "tools": {
                    "workspaceRoot": "%s",
                    "maxFileBytes": 1048576
                  }
                }
                """.formatted(tempDir.toString().replace("\\", "\\\\")));

        HarnessConfig config = new ConfigLoader().load(configFile);
        DoctorService service = new DoctorService();
        DoctorReport report = service.diagnose(config, configFile);

        assertThat(report.results()).isNotEmpty();
        assertThat(report.isHealthy()).isTrue();
    }

    @Test
    void skipsUpdateCheckByDefault(@TempDir Path tempDir) throws Exception {
        Path configFile = writeMinimalConfig(tempDir);
        HarnessConfig config = new ConfigLoader().load(configFile);
        DoctorReport report = new DoctorService().diagnose(config, configFile);

        assertThat(report.results()).noneMatch(r -> r.category().equals("Version"));
    }

    @Test
    void includesUpdateCheckWhenRequested(@TempDir Path tempDir) throws Exception {
        Path configFile = writeMinimalConfig(tempDir);
        HarnessConfig config = new ConfigLoader().load(configFile);
        UpdateChecker unreachable = new UpdateChecker(URI.create("http://127.0.0.1:1"));
        DoctorReport report = new DoctorService(unreachable).diagnose(config, configFile, true);

        assertThat(report.results()).anyMatch(r -> r.category().equals("Version"));
    }

    @Test
    void flagsMissingLocalModelAsError(@TempDir Path tempDir) throws Exception {
        String baseUrl = startFakeOllama("{\"models\": [{\"name\": \"llama3.1:8b\"}]}");
        Path configFile = writeConfigWithModel(tempDir, baseUrl, "qwen2.5-coder");
        HarnessConfig config = new ConfigLoader().load(configFile);

        DoctorReport report = new DoctorService().diagnose(config, configFile);

        assertThat(report.isHealthy()).isFalse();
        assertThat(report.results()).anyMatch(r -> r.status() == Status.ERROR
                && r.name().equals("test-provider Model")
                && r.message().contains("ollama pull qwen2.5-coder"));
    }

    @Test
    void confirmsPulledLocalModelIsOk(@TempDir Path tempDir) throws Exception {
        String baseUrl = startFakeOllama("{\"models\": [{\"name\": \"qwen2.5-coder\"}]}");
        Path configFile = writeConfigWithModel(tempDir, baseUrl, "qwen2.5-coder");
        HarnessConfig config = new ConfigLoader().load(configFile);

        DoctorReport report = new DoctorService().diagnose(config, configFile);

        assertThat(report.results()).anyMatch(r -> r.status() == Status.OK
                && r.name().equals("test-provider Model"));
    }

    @Test
    void flagsPaidProviderWithNoCostCapAsWarning(@TempDir Path tempDir) throws Exception {
        Path configFile = writeConfigWithPaidProvider(tempDir, null);
        HarnessConfig config = new ConfigLoader().load(configFile);

        DoctorReport report = new DoctorService().diagnose(config, configFile);

        assertThat(report.results()).anyMatch(r -> r.status() == Status.WARNING
                && r.name().equals("Cost guardrail")
                && r.message().contains("unlimited spend"));
    }

    @Test
    void confirmsCostCapConfiguredIsOk(@TempDir Path tempDir) throws Exception {
        Path configFile = writeConfigWithPaidProvider(tempDir, """
                "reliability": {
                  "maxCostUsdPerTask": 1.0,
                  "maxCumulativeCostUsd": 20.0
                },
                """);
        HarnessConfig config = new ConfigLoader().load(configFile);

        DoctorReport report = new DoctorService().diagnose(config, configFile);

        assertThat(report.results()).anyMatch(r -> r.status() == Status.OK
                && r.name().equals("Cost guardrail"));
    }

    private static Path writeConfigWithPaidProvider(Path tempDir, String reliabilityBlock) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "providers": [
                    {
                      "id": "test-frontier",
                      "tier": "FRONTIER_CLOUD",
                      "baseUrl": "http://127.0.0.1:1/v1",
                      "modelName": "gpt-4o",
                      "temperature": 0.0,
                      "timeoutSeconds": 10,
                      "toolsEnabled": false,
                      "enabled": true,
                      "apiKeyEnv": "TEST_KEY_NOT_SET",
                      "costPerMillionInputTokens": 3.0,
                      "costPerMillionOutputTokens": 15.0
                    }
                  ],
                  %s
                  "tools": {
                    "workspaceRoot": "%s",
                    "maxFileBytes": 1048576
                  }
                }
                """.formatted(reliabilityBlock == null ? "" : reliabilityBlock,
                        tempDir.toString().replace("\\", "\\\\")));
        return configFile;
    }

    private String startFakeOllama(String tagsBody) throws Exception {
        ollamaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollamaServer.createContext("/api/tags", exchange -> {
            byte[] response = tagsBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        ollamaServer.setExecutor(null);
        ollamaServer.start();
        return "http://127.0.0.1:" + ollamaServer.getAddress().getPort() + "/v1";
    }

    private static Path writeConfigWithModel(Path tempDir, String baseUrl, String modelName) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "providers": [
                    {
                      "id": "test-provider",
                      "tier": "SMALL_LOCAL",
                      "baseUrl": "%s",
                      "modelName": "%s",
                      "temperature": 0.0,
                      "timeoutSeconds": 10,
                      "toolsEnabled": false,
                      "enabled": true,
                      "apiKeyEnv": "TEST_KEY_NOT_SET",
                      "costPerMillionInputTokens": 0.0,
                      "costPerMillionOutputTokens": 0.0
                    }
                  ],
                  "tools": {
                    "workspaceRoot": "%s",
                    "maxFileBytes": 1048576
                  }
                }
                """.formatted(baseUrl, modelName, tempDir.toString().replace("\\", "\\\\")));
        return configFile;
    }

    private static Path writeMinimalConfig(Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "providers": [
                    {
                      "id": "test-provider",
                      "tier": "SMALL_LOCAL",
                      "baseUrl": "http://127.0.0.1:1/v1",
                      "modelName": "qwen2.5-coder",
                      "temperature": 0.0,
                      "timeoutSeconds": 10,
                      "toolsEnabled": false,
                      "enabled": true,
                      "apiKeyEnv": "TEST_KEY_NOT_SET",
                      "costPerMillionInputTokens": 0.0,
                      "costPerMillionOutputTokens": 0.0
                    }
                  ],
                  "tools": {
                    "workspaceRoot": "%s",
                    "maxFileBytes": 1048576
                  }
                }
                """.formatted(tempDir.toString().replace("\\", "\\\\")));
        return configFile;
    }
}
