package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayHttpServerTest {

    @TempDir
    Path tempDir;

    private GatewayHttpServer server;
    private HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private HarnessConfig config() {
        ProviderConfig provider = new ProviderConfig("small-local", ModelTier.SMALL_LOCAL, "http://localhost:8080/v1/",
                "small-model", null, 0.1, 30, true, true);
        ToolConfig toolConfig = new ToolConfig(tempDir.toString(), 262_144, false);
        return new HarnessConfig(List.of(provider), new RoutingConfig(240, true), toolConfig);
    }

    /** Stubs {@link HarnessOrchestrator#run} so tests exercise the gateway's HTTP/mapping layer
     * without making a real model call. */
    private static final class StubOrchestrator extends HarnessOrchestrator {
        private final Outcome outcome;

        StubOrchestrator(HarnessConfig config, Outcome outcome) {
            super(config);
            this.outcome = outcome;
        }

        @Override
        public Outcome run(TaskRequest task) {
            return outcome;
        }
    }

    private void startServer(String bindAddress, String token, HarnessOrchestrator orchestrator) throws IOException {
        server = new GatewayHttpServer(bindAddress, 0, config(), token, orchestrator);
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @BeforeEach
    void setUp() throws IOException {
        HarnessConfig config = config();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "Hello from CastCLI", List.of(), List.of(), 5L, false, 3L, 4L, 0.0);
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void chatCompletionsReturnsOpenAiShapedResponse() throws Exception {
        HttpResponse<String> response = post("""
                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = mapper.readTree(response.body());
        assertThat(json.path("object").asText()).isEqualTo("chat.completion");
        assertThat(json.path("choices").get(0).path("message").path("content").asText()).isEqualTo("Hello from CastCLI");
        assertThat(json.path("choices").get(0).path("message").path("role").asText()).isEqualTo("assistant");
        assertThat(json.path("usage").path("prompt_tokens").asLong()).isEqualTo(3L);
        assertThat(json.path("usage").path("completion_tokens").asLong()).isEqualTo(4L);
        assertThat(json.path("usage").path("total_tokens").asLong()).isEqualTo(7L);
    }

    @Test
    void chatCompletionsFlattensMultiTurnMessages() throws Exception {
        HttpResponse<String> response = post("""
                {"messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}]}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsStreamingRequests() throws Exception {
        HttpResponse<String> response = post("""
                {"messages":[{"role":"user","content":"hi"}],"stream":true}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("stream=true");
    }

    @Test
    void rejectsClientSuppliedTools() throws Exception {
        HttpResponse<String> response = post("""
                {"messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"x"}}]}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("tools");
    }

    @Test
    void rejectsEmptyMessages() throws Exception {
        HttpResponse<String> response = post("""
                {"messages":[]}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void getMethodNotAllowedOnChatCompletions() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void modelsEndpointListsEnabledProviders() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/models"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = mapper.readTree(response.body());
        assertThat(json.path("object").asText()).isEqualTo("list");
        assertThat(json.path("data").get(0).path("id").asText()).isEqualTo("small-local");
    }

    @Test
    void refusesNonLoopbackBindWithoutToken() {
        assertThatThrownBy(() -> new GatewayHttpServer("0.0.0.0", 0, config(), null,
                new StubOrchestrator(config(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--token");
    }

    @Test
    void requiresBearerTokenWhenConfigured() throws Exception {
        tearDown();
        HarnessConfig config = config();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "secure hello", List.of(), List.of(), 1L, false, 1L, 1L, 0.0);
        startServer("127.0.0.1", "s3cr3t", new StubOrchestrator(config, outcome));

        HttpRequest noAuth = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"messages":[{"role":"user","content":"hi"}]}
                        """))
                .build();
        HttpResponse<String> noAuthResponse = httpClient.send(noAuth, HttpResponse.BodyHandlers.ofString());
        assertThat(noAuthResponse.statusCode()).isEqualTo(401);

        HttpRequest wrongAuth = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer wrong-token")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"messages":[{"role":"user","content":"hi"}]}
                        """))
                .build();
        HttpResponse<String> wrongAuthResponse = httpClient.send(wrongAuth, HttpResponse.BodyHandlers.ofString());
        assertThat(wrongAuthResponse.statusCode()).isEqualTo(401);

        HttpRequest rightAuth = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer s3cr3t")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"messages":[{"role":"user","content":"hi"}]}
                        """))
                .build();
        HttpResponse<String> rightAuthResponse = httpClient.send(rightAuth, HttpResponse.BodyHandlers.ofString());
        assertThat(rightAuthResponse.statusCode()).isEqualTo(200);
    }
}
