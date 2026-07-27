package dev.justnels.castcli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsAProvider() throws Exception {
        Path config = tempDir.resolve("harness.json");
        Files.writeString(config, """
                {
                  "providers": [{
                    "id": "tiny", "tier": "SMALL_LOCAL", "baseUrl": "http://localhost/v1/",
                    "modelName": "tiny", "temperature": 0.0, "timeoutSeconds": 10,
                    "toolsEnabled": false, "enabled": true
                  }]
                }
                """);

        HarnessConfig loaded = new ConfigLoader().load(config);

        assertThat(loaded.providers()).singleElement().extracting(ProviderConfig::id).isEqualTo("tiny");
        assertThat(loaded.routing().quickPromptMaxChars()).isEqualTo(240);
        assertThat(loaded.observability().enabled()).isFalse();
        assertThat(loaded.mcpAudit().enabled()).isTrue();
        assertThat(loaded.mcpAudit().path()).isEqualTo(".cast/metrics/mcp-usage.jsonl");
    }

    @Test
    void loadsObservabilityExportConfiguration() throws Exception {
        Path config = tempDir.resolve("observability.json");
        Files.writeString(config, """
                {
                  "providers": [{
                    "id": "tiny", "tier": "SMALL_LOCAL", "baseUrl": "http://localhost/v1/",
                    "modelName": "tiny", "timeoutSeconds": 10, "enabled": true
                  }],
                  "observability": {
                    "enabled": true,
                    "serviceName": "test-harness",
                    "otlpEnabled": true,
                    "otlpEndpoint": "http://collector:4317",
                    "otlpHeaders": {"Authorization": "Basic redacted"},
                    "capturePrompts": false
                  }
                }
                """);

        ObservabilityConfig observability = new ConfigLoader().load(config).observability();

        assertThat(observability.enabled()).isTrue();
        assertThat(observability.serviceName()).isEqualTo("test-harness");
        assertThat(observability.otlpHeaders()).containsEntry("Authorization", "Basic redacted");
    }

    @Test
    void loadsEveryShippedConfigFile() throws Exception {
        Path configDir = Path.of("config");
        try (var files = Files.list(configDir)) {
            List<Path> jsonFiles = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertThat(jsonFiles).isNotEmpty();
            for (Path file : jsonFiles) {
                HarnessConfig loaded = new ConfigLoader().load(file);
                assertThat(loaded.providers()).isNotEmpty();
                for (ProviderConfig provider : loaded.providers()) {
                    assertThat(provider.baseUrl())
                            .as("baseUrl for provider '%s' in %s", provider.id(), file)
                            .startsWith("http");
                }
            }
        }
    }
}

