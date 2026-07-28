package dev.justnels.castcli.gateway;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throwaway-turned-regression check for the load-bearing assumption behind client-tool
 * passthrough: that {@link JsonRawSchema} property values actually reach the outbound provider
 * request rather than being dropped/ignored by the OpenAI-compatible serialization path. If this
 * ever fails, {@code ClientToolSupport}'s JSON-Schema-to-ToolSpecification conversion needs a real
 * recursive schema builder instead of the raw-schema escape hatch.
 */
class JsonRawSchemaWireVerificationTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void jsonRawSchemaPropertiesReachTheOutboundRequestBody() throws Exception {
        CompletableFuture<String> capturedBody = new CompletableFuture<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBody.complete(body);
            byte[] response = """
                    {"id":"x","object":"chat.completion","created":1,"model":"m",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/")
                .apiKey("test")
                .modelName("test-model")
                .timeout(Duration.ofSeconds(10))
                .maxRetries(0)
                .build();

        JsonObjectSchema parameters = JsonObjectSchema.builder()
                .addProperty("city", JsonRawSchema.from("""
                        {"type":"string","description":"City name"}"""))
                .addProperty("unit", JsonRawSchema.from("""
                        {"type":"string","enum":["celsius","fahrenheit"]}"""))
                .required("city")
                .build();
        ToolSpecification tool = ToolSpecification.builder()
                .name("get_weather")
                .description("Look up the weather")
                .parameters(parameters)
                .build();

        model.chat(ChatRequest.builder()
                .messages(UserMessage.from("weather in Paris?"))
                .toolSpecifications(tool)
                .build());

        String requestBody = capturedBody.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(requestBody).contains("\"get_weather\"");
        assertThat(requestBody).contains("\"city\"");
        assertThat(requestBody).contains("City name");
        assertThat(requestBody).contains("\"unit\"");
        assertThat(requestBody).contains("celsius");
        assertThat(requestBody).contains("fahrenheit");
    }
}
