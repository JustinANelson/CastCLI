package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import dev.justnels.castcli.reliability.BudgetExceededException;
import dev.justnels.castcli.reliability.ProviderExecutionException;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase-1 handler for {@code POST /v1/chat/completions}: non-streaming, no client-tool
 * passthrough. Flattens the OpenAI {@code messages[]} array into a single prompt (multi-turn
 * fidelity is deferred to a later phase) and routes it through the same
 * {@link HarnessOrchestrator#run(TaskRequest)} call the {@code ask} CLI command uses.
 */
final class ChatCompletionsHandler implements HttpHandler {

    private final HarnessOrchestrator orchestrator;
    private final ObjectMapper mapper;

    ChatCompletionsHandler(HarnessOrchestrator orchestrator, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            GatewayErrors.methodNotAllowed(exchange);
            return;
        }

        JsonNode request;
        try {
            request = mapper.readTree(exchange.getRequestBody());
        } catch (IOException e) {
            GatewayErrors.send(exchange, mapper, 400, "invalid_request_error", "Request body is not valid JSON.");
            return;
        }

        if (request.path("stream").asBoolean(false)) {
            GatewayErrors.send(exchange, mapper, 400, "invalid_request_error",
                    "stream=true is not yet supported by this CastCLI gateway build.");
            return;
        }
        if (hasContent(request.path("tools")) || hasContent(request.path("tool_choice"))) {
            GatewayErrors.send(exchange, mapper, 400, "invalid_request_error",
                    "Client-supplied 'tools'/'tool_choice' are not yet supported by this CastCLI gateway build.");
            return;
        }

        String prompt;
        try {
            prompt = flattenMessages(request.path("messages"));
        } catch (IllegalArgumentException e) {
            GatewayErrors.send(exchange, mapper, 400, "invalid_request_error", e.getMessage());
            return;
        }

        try {
            TaskRequest task = new TaskRequest(prompt, Workload.AUTO, null);
            HarnessOrchestrator.Outcome outcome = orchestrator.run(task);
            sendCompletion(exchange, outcome);
        } catch (BudgetExceededException e) {
            GatewayErrors.send(exchange, mapper, 429, "budget_exceeded", e.getMessage());
        } catch (ProviderExecutionException e) {
            GatewayErrors.send(exchange, mapper, 502, "provider_error", e.getMessage());
        } catch (IllegalArgumentException e) {
            GatewayErrors.send(exchange, mapper, 400, "invalid_request_error", e.getMessage());
        } catch (IllegalStateException e) {
            GatewayErrors.send(exchange, mapper, 503, "no_provider_available", e.getMessage());
        } catch (RuntimeException e) {
            GatewayErrors.send(exchange, mapper, 500, "internal_error", "The gateway failed to complete this request.");
        }
    }

    private static boolean hasContent(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return false;
        }
        return !(node.isArray() && node.isEmpty());
    }

    /**
     * Flattens an OpenAI {@code messages[]} array into a single prompt string, role-tagged and in
     * order. This is a phase-1 simplification: true multi-turn/system-prompt fidelity is tracked
     * as a follow-on phase.
     */
    private static String flattenMessages(JsonNode messages) {
        if (!messages.isArray() || messages.isEmpty()) {
            throw new IllegalArgumentException("'messages' must be a non-empty array.");
        }
        StringBuilder prompt = new StringBuilder();
        for (JsonNode message : messages) {
            String role = message.path("role").asText("user");
            JsonNode contentNode = message.path("content");
            if (!contentNode.isTextual()) {
                throw new IllegalArgumentException(
                        "Only string 'content' is supported in this CastCLI gateway build (message parts arrays are not).");
            }
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append(role).append(": ").append(contentNode.asText());
        }
        return prompt.toString();
    }

    private void sendCompletion(HttpExchange exchange, HarnessOrchestrator.Outcome outcome) throws IOException {
        ObjectNode response = mapper.createObjectNode();
        response.put("id", "chatcmpl-" + UUID.randomUUID());
        response.put("object", "chat.completion");
        response.put("created", Instant.now().getEpochSecond());
        response.put("model", outcome.provider().modelName());

        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", outcome.answer());
        choice.put("finish_reason", "stop");

        ObjectNode usage = response.putObject("usage");
        usage.put("prompt_tokens", outcome.inputTokens());
        usage.put("completion_tokens", outcome.outputTokens());
        usage.put("total_tokens", outcome.inputTokens() + outcome.outputTokens());

        byte[] bytes = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
