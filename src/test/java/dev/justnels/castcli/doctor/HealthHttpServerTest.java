package dev.justnels.castcli.doctor;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HealthHttpServerTest {

    @TempDir
    Path tempDir;

    private HealthHttpServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws IOException {
        ProviderConfig provider = new ProviderConfig("small-local", ModelTier.SMALL_LOCAL, "http://localhost:8080/v1/",
                "small-model", null, 0.1, 30, true, true);
        ToolConfig toolConfig = new ToolConfig(tempDir.toString(), 262_144, false);
        HarnessConfig config = new HarnessConfig(List.of(provider), new RoutingConfig(240, true), toolConfig);
        Path configPath = tempDir.resolve("harness.json");

        server = new HealthHttpServer(0, config, configPath);
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void healthzEndpointReturnsOkAndJson() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/healthz"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));
        assertThat(response.body()).contains("results");
    }

    @Test
    void metricsEndpointReturnsPrometheusFormat() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("castcli_health_status");
        assertThat(response.body()).contains("castcli_jvm_memory_total_bytes");
    }

    @Test
    void healthzEndpointRejectsPostMethod() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/healthz"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }
}
