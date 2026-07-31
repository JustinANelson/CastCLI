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
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
     * without making a real model call. Overrides the history-aware overload, since that's what
     * {@code ChatCompletionsHandler} actually calls (with an empty history for a single-turn
     * request) -- overriding the single-arg convenience method here would silently not intercept
     * anything and fall through to a real network call. */
    private static class StubOrchestrator extends HarnessOrchestrator {
        private final Outcome outcome;

        StubOrchestrator(HarnessConfig config, Outcome outcome) {
            super(config);
            this.outcome = outcome;
        }

        @Override
        public Outcome run(TaskRequest task, List<ChatMessage> history) {
            return outcome;
        }
    }

    private void startServer(String bindAddress, String token, HarnessOrchestrator orchestrator) throws IOException {
        server = new GatewayHttpServer(bindAddress, 0, config(), token, orchestrator);
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    private void startServer(String bindAddress, String token, HarnessOrchestrator orchestrator,
                             GatewayLimits limits) throws IOException {
        server = new GatewayHttpServer(bindAddress, 0, config(), token, orchestrator, limits);
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
    void chatCompletionsSendsHistoryAsRealMessagesNotAFlattenedBlob() throws Exception {
        tearDown();
        HarnessConfig config = config();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "ok", List.of(), List.of(), 1L, false, 1L, 1L, 0.0);
        List<List<ChatMessage>> captured = new java.util.ArrayList<>();
        List<String> capturedPrompt = new java.util.ArrayList<>();
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome) {
            @Override
            public Outcome run(TaskRequest task, List<ChatMessage> history) {
                captured.add(history);
                capturedPrompt.add(task.prompt());
                return outcome;
            }
        });

        HttpResponse<String> response = post("""
                {"messages":[
                  {"role":"system","content":"be brief"},
                  {"role":"user","content":"first question"},
                  {"role":"assistant","content":"first answer"},
                  {"role":"user","content":"second question"}
                ]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(captured).hasSize(1);
        List<ChatMessage> history = captured.getFirst();
        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isInstanceOf(dev.langchain4j.data.message.SystemMessage.class);
        assertThat(((dev.langchain4j.data.message.SystemMessage) history.get(0)).text()).isEqualTo("be brief");
        assertThat(history.get(1)).isInstanceOf(dev.langchain4j.data.message.UserMessage.class);
        assertThat(((dev.langchain4j.data.message.UserMessage) history.get(1)).singleText()).isEqualTo("first question");
        assertThat(history.get(2)).isInstanceOf(dev.langchain4j.data.message.AiMessage.class);
        assertThat(((dev.langchain4j.data.message.AiMessage) history.get(2)).text()).isEqualTo("first answer");
        // The current turn is not duplicated into history -- it stays the TaskRequest prompt so
        // fastPath/routing/memory heuristics keep working exactly as the single-turn path always has.
        assertThat(capturedPrompt.getFirst()).isEqualTo("second question");
    }

    @Test
    void chatCompletionsFallsBackToFlatteningWhenConversationDoesNotEndInUserMessage() throws Exception {
        tearDown();
        HarnessConfig config = config();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "ok", List.of(), List.of(), 1L, false, 1L, 1L, 0.0);
        List<List<ChatMessage>> captured = new java.util.ArrayList<>();
        List<String> capturedPrompt = new java.util.ArrayList<>();
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome) {
            @Override
            public Outcome run(TaskRequest task, List<ChatMessage> history) {
                captured.add(history);
                capturedPrompt.add(task.prompt());
                return outcome;
            }
        });

        HttpResponse<String> response = post("""
                {"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(captured.getFirst()).isEmpty();
        assertThat(capturedPrompt.getFirst()).contains("user: hi").contains("assistant: hello");
    }

    @Test
    void streamingReturnsChatCompletionChunksTerminatedByDone() throws Exception {
        tearDown();
        HarnessConfig config = config();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "hi there", List.of(), List.of(), 5L, false, 2L, 3L, 0.0);
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome) {
            @Override
            public Outcome runStreaming(TaskRequest task, java.util.function.Consumer<String> onToken,
                                          java.util.function.BooleanSupplier cancelled, List<ChatMessage> history) {
                onToken.accept("hi");
                onToken.accept(" there");
                return outcome;
            }
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"messages":[{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":true}}
                        """))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).contains("text/event-stream"));
        List<String> dataLines = response.body().lines().filter(line -> line.startsWith("data: ")).toList();
        assertThat(dataLines.getLast()).isEqualTo("data: [DONE]");

        JsonNode firstChunk = mapper.readTree(dataLines.get(0).substring("data: ".length()));
        assertThat(firstChunk.path("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(firstChunk.path("choices").get(0).path("delta").path("role").asText()).isEqualTo("assistant");
        assertThat(firstChunk.path("choices").get(0).path("delta").path("content").asText()).isEqualTo("hi");

        JsonNode secondChunk = mapper.readTree(dataLines.get(1).substring("data: ".length()));
        assertThat(secondChunk.path("choices").get(0).path("delta").has("role")).isFalse();
        assertThat(secondChunk.path("choices").get(0).path("delta").path("content").asText()).isEqualTo(" there");

        JsonNode finishChunk = mapper.readTree(dataLines.get(2).substring("data: ".length()));
        assertThat(finishChunk.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");

        JsonNode usageChunk = mapper.readTree(dataLines.get(3).substring("data: ".length()));
        assertThat(usageChunk.path("choices").isEmpty()).isTrue();
        assertThat(usageChunk.path("usage").path("prompt_tokens").asLong()).isEqualTo(2L);
        assertThat(usageChunk.path("usage").path("completion_tokens").asLong()).isEqualTo(3L);
    }

    @Test
    void streamingDeliversChunksIncrementallyRatherThanBuffering() throws Exception {
        tearDown();
        HarnessConfig config = config();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "done", List.of(), List.of(), 1L, false, 1L, 1L, 0.0);
        long delayMillis = 1500;
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome) {
            @Override
            public Outcome runStreaming(TaskRequest task, java.util.function.Consumer<String> onToken,
                                          java.util.function.BooleanSupplier cancelled, List<ChatMessage> history) {
                onToken.accept("first");
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onToken.accept("second");
                return outcome;
            }
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"messages":[{"role":"user","content":"hi"}],"stream":true}
                        """))
                .build();

        long sentAt = System.currentTimeMillis();
        HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String firstDataLine = reader.readLine();
            while (firstDataLine != null && !firstDataLine.startsWith("data: ")) {
                firstDataLine = reader.readLine();
            }
            long elapsed = System.currentTimeMillis() - sentAt;
            assertThat(firstDataLine).contains("\"content\":\"first\"");
            assertThat(elapsed).isLessThan(delayMillis);
        }
    }

    @Test
    void streamingStopsPromptlyWhenClientDisconnects() throws Exception {
        tearDown();
        HarnessConfig config = config();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "unused", List.of(), List.of(), 1L, false, 0L, 0L, 0.0);
        java.util.concurrent.atomic.AtomicBoolean observedCancellation = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CountDownLatch cancellationObserved = new java.util.concurrent.CountDownLatch(1);
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome) {
            @Override
            public Outcome runStreaming(TaskRequest task, java.util.function.Consumer<String> onToken,
                                          java.util.function.BooleanSupplier cancelled, List<ChatMessage> history) {
                while (!cancelled.getAsBoolean()) {
                    onToken.accept("tick ");
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                observedCancellation.set(true);
                cancellationObserved.countDown();
                return outcome;
            }
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"messages":[{"role":"user","content":"hi"}],"stream":true}
                        """))
                .build();

        HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        response.body().read();
        response.body().close();

        assertThat(cancellationObserved.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(observedCancellation.get()).isTrue();
    }

    @Test
    void rejectsEmptyMessages() throws Exception {
        HttpResponse<String> response = post("""
                {"messages":[]}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsOversizedRequestBeforeModelExecution() throws Exception {
        tearDown();
        HarnessConfig config = config();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "unused", List.of(), List.of(), 1L, false, 0L, 0L, 0.0);
        GatewayLimits limits = new GatewayLimits(32, 2, 1, 0, 10, 10, 10, 32);
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome) {
            @Override public Outcome run(TaskRequest task, List<ChatMessage> history) {
                calls.incrementAndGet();
                return outcome;
            }
        }, limits);

        HttpResponse<String> response = post("{\"messages\":[{\"role\":\"user\",\"content\":\"payload-too-large\"}]}");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(calls).hasValue(0);
        assertThat(server.metrics().oversized()).isEqualTo(1);
    }

    @Test
    void rejectsConcurrentRequestWhenAdmissionIsFull() throws Exception {
        tearDown();
        HarnessConfig config = config();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "ok", List.of(), List.of(), 1L, false, 1L, 1L, 0.0);
        GatewayLimits limits = new GatewayLimits(4096, 1, 1, 0, 10, 10, 20, 1024);
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome) {
            @Override public Outcome run(TaskRequest task, List<ChatMessage> history) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return outcome;
            }
        }, limits);

        HttpRequest firstRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"first\"}]}"))
                .build();
        var first = httpClient.sendAsync(firstRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> overloaded = post("{\"messages\":[{\"role\":\"user\",\"content\":\"second\"}]}");
        release.countDown();

        assertThat(overloaded.statusCode()).isEqualTo(503);
        assertThat(overloaded.headers().firstValue("Retry-After")).contains("1");
        assertThat(first.get(2, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(server.metrics().rejected()).isEqualTo(1);
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

    @Test
    void clientToolsReturnsUnexecutedToolCallsWithRealFinishReason() throws Exception {
        tearDown();
        HarnessConfig config = config();
        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("call_abc").name("get_weather").arguments("{\"city\":\"Paris\"}").build();
        HarnessOrchestrator.ClientToolOutcome outcome = new HarnessOrchestrator.ClientToolOutcome(
                config.providers().getFirst(), null, List.of(toolCall), "tool_calls", 5L, 10L, 2L, 0.0);
        startServer("127.0.0.1", null, new StubOrchestrator(config, null) {
            @Override
            public ClientToolOutcome runWithClientTools(TaskRequest task, List<ChatMessage> messages,
                                                          List<ToolSpecification> toolSpecifications, ToolChoice toolChoice) {
                return outcome;
            }
        });

        HttpResponse<String> response = post("""
                {"messages":[{"role":"user","content":"weather in Paris?"}],
                 "tools":[{"type":"function","function":{"name":"get_weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = mapper.readTree(response.body());
        JsonNode message = json.path("choices").get(0).path("message");
        assertThat(message.path("content").isNull()).isTrue();
        assertThat(message.path("tool_calls").get(0).path("id").asText()).isEqualTo("call_abc");
        assertThat(message.path("tool_calls").get(0).path("type").asText()).isEqualTo("function");
        assertThat(message.path("tool_calls").get(0).path("function").path("name").asText()).isEqualTo("get_weather");
        assertThat(message.path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"city\":\"Paris\"}");
        assertThat(json.path("choices").get(0).path("finish_reason").asText()).isEqualTo("tool_calls");
    }

    @Test
    void clientToolsCanAnswerWithTextInsteadOfCalling() throws Exception {
        tearDown();
        HarnessConfig config = config();
        HarnessOrchestrator.ClientToolOutcome outcome = new HarnessOrchestrator.ClientToolOutcome(
                config.providers().getFirst(), "It's sunny.", List.of(), "stop", 5L, 8L, 3L, 0.0);
        startServer("127.0.0.1", null, new StubOrchestrator(config, null) {
            @Override
            public ClientToolOutcome runWithClientTools(TaskRequest task, List<ChatMessage> messages,
                                                          List<ToolSpecification> toolSpecifications, ToolChoice toolChoice) {
                return outcome;
            }
        });

        HttpResponse<String> response = post("""
                {"messages":[{"role":"user","content":"weather in Paris?"}],
                 "tools":[{"type":"function","function":{"name":"get_weather","parameters":{"type":"object","properties":{}}}}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = mapper.readTree(response.body());
        JsonNode message = json.path("choices").get(0).path("message");
        assertThat(message.path("content").asText()).isEqualTo("It's sunny.");
        assertThat(message.has("tool_calls")).isFalse();
        assertThat(json.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
    }

    @Test
    void rejectsStreamingCombinedWithClientTools() throws Exception {
        HttpResponse<String> response = post("""
                {"messages":[{"role":"user","content":"hi"}],"stream":true,
                 "tools":[{"type":"function","function":{"name":"noop"}}]}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("stream=true");
    }

    @Test
    void clientToolsRejectsMalformedToolDefinition() throws Exception {
        HttpResponse<String> response = post("""
                {"messages":[{"role":"user","content":"hi"}],
                 "tools":[{"type":"unsupported_type"}]}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @Tag("performance")
    void gatewayLoadProbeReportsTailLatencyAndThroughput() {
        int requests = Integer.getInteger("castcli.performance.requests", 64);
        List<java.util.concurrent.CompletableFuture<Long>> futures = new java.util.ArrayList<>();
        long batchStarted = System.nanoTime();
        for (int i = 0; i < requests; i++) {
            long started = System.nanoTime();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"load\"}]}"))
                    .build();
            futures.add(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> (System.nanoTime() - started) / 1_000_000));
        }
        java.util.concurrent.CompletableFuture.allOf(
                futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();

        List<Long> durations = futures.stream().map(java.util.concurrent.CompletableFuture::join)
                .sorted().toList();
        long p95 = durations.get(Math.max(0, (int) Math.ceil(durations.size() * 0.95) - 1));
        long threshold = Long.getLong("castcli.performance.gatewayP95Ms", 10_000L);
        double throughput = requests / ((System.nanoTime() - batchStarted) / 1_000_000_000.0);

        assertThat(durations).hasSize(requests).allMatch(duration -> duration >= 0);
        assertThat(p95).isLessThan(threshold);
        System.out.printf("gateway-load requests=%d p50=%dms p95=%dms throughput=%.2f/s%n",
                requests, durations.get(durations.size() / 2), p95, throughput);
    }

    @Test
    @Tag("chaos")
    void rejectsOversizedChunkedBodyWithoutContentLength() throws Exception {
        tearDown();
        HarnessConfig config = config();
        HarnessOrchestrator.Outcome outcome = new HarnessOrchestrator.Outcome(
                config.providers().getFirst(), "unused", List.of(), List.of(), 1L, false, 0L, 0L, 0.0);
        GatewayLimits limits = new GatewayLimits(32, 2, 1, 0, 10, 10, 10, 32);
        startServer("127.0.0.1", null, new StubOrchestrator(config, outcome), limits);

        byte[] payload = "{\"messages\":[{\"role\":\"user\",\"content\":\"chunked-payload-too-large\"}]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(server.metrics().oversized()).isEqualTo(1);
    }
}
