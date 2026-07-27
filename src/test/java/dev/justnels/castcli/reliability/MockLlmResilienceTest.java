package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ReliabilityConfig;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.testutil.MockLlmServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockLlmResilienceTest {
    private MockLlmServer mockServer;
    private ReliabilityConfig config;
    private ProviderHealthRegistry healthRegistry;
    private ReliabilityExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockLlmServer();
        config = new ReliabilityConfig(2, 50, 200, 2, 5, 10, 8, Map.of());
        healthRegistry = new ProviderHealthRegistry(config);
        executor = new ReliabilityExecutor(config, healthRegistry);
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @Test
    @DisplayName("Successfully executes call against mock provider")
    void executesCallSuccessfully() {
        mockServer.setMode(MockLlmServer.ResponseMode.SUCCESS);
        mockServer.setSuccessMessage("Hello from mock server!");

        ProviderConfig provider = new ProviderConfig("mock-p1", ModelTier.SMALL_LOCAL, "openai", "gpt-4o-mini", mockServer.getBaseUrl(), 0.0, 1, false, false);
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(mockServer.getBaseUrl())
                .apiKey("test-key")
                .modelName("mock-model")
                .maxRetries(0)
                .logRequests(false)
                .build();

        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Ping")).build();
        long deadline = System.nanoTime() + 10_000_000_000L;
        ChatResponse response = executor.execute(provider, () -> model.chat(request), true, deadline);

        assertThat(response.aiMessage().text()).isEqualTo("Hello from mock server!");
        assertThat(mockServer.getRequestCount()).isEqualTo(1);
        assertThat(healthRegistry.isAvailable(provider)).isTrue();
    }

    @Test
    @DisplayName("Classifies rate limit failure (429) as retryable and updates provider health")
    void handlesRateLimit429Failure() {
        mockServer.setMode(MockLlmServer.ResponseMode.RATE_LIMIT_429);
        ProviderConfig provider = new ProviderConfig("mock-p2", ModelTier.SMALL_LOCAL, "openai", "gpt-4o-mini", mockServer.getBaseUrl(), 0.0, 1, false, false);

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(mockServer.getBaseUrl())
                .apiKey("test-key")
                .modelName("mock-model")
                .maxRetries(0)
                .logRequests(false)
                .build();

        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Ping")).build();
        long deadline = System.nanoTime() + 10_000_000_000L;
        assertThatThrownBy(() -> executor.execute(provider, () -> model.chat(request), true, deadline))
                .isInstanceOf(ProviderExecutionException.class);

        assertThat(mockServer.getRequestCount()).isEqualTo(2); // Initial attempt + 1 retry
        assertThat(healthRegistry.isAvailable(provider)).isFalse();
    }

    @Test
    @DisplayName("Trips circuit breaker after consecutive failures threshold reached")
    void tripsCircuitBreakerAfterFailures() {
        mockServer.setMode(MockLlmServer.ResponseMode.SERVER_ERROR_500);
        ProviderConfig provider = new ProviderConfig("mock-p3", ModelTier.SMALL_LOCAL, "openai", "gpt-4o-mini", mockServer.getBaseUrl(), 0.0, 1, false, false);

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(mockServer.getBaseUrl())
                .apiKey("test-key")
                .modelName("mock-model")
                .maxRetries(0)
                .logRequests(false)
                .build();

        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Ping")).build();
        long deadline = System.nanoTime() + 10_000_000_000L;

        assertThatThrownBy(() -> executor.execute(provider, () -> model.chat(request), true, deadline))
                .isInstanceOf(ProviderExecutionException.class);

        assertThat(healthRegistry.consecutiveFailures(provider)).isGreaterThanOrEqualTo(2);
        assertThat(healthRegistry.isAvailable(provider)).isFalse();
    }
}
