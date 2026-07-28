package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import dev.justnels.castcli.reliability.BudgetExceededException;
import dev.justnels.castcli.reliability.ProviderExecutionException;
import dev.justnels.castcli.security.GuardrailFilter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handler for {@code POST /v1/chat/completions}. Supports non-streaming responses, SSE streaming
 * (Phase 2), and client-side tool passthrough (Phase 3, non-streaming only -- combining streaming
 * with tools is rejected). When tools are involved, {@link ClientToolSupport} reconstructs the real
 * message list for the whole conversation, since tool-call round trips cannot survive flattening.
 * When no tools are involved (Phase 4), a request ending in a {@code role:"user"} message is split
 * into prior-turn history plus the current turn via {@link ClientToolSupport#splitLastUserTurn} --
 * history is sent to the model as real messages, while the current turn's text still flows through
 * {@code TaskRequest} so fastPath/routing/memory-augmentation heuristics keep working unchanged. A
 * request that doesn't end in a user message (rare for a plain chat-completions call) falls back to
 * the original whole-conversation flattening instead of being rejected. Routes through the same
 * {@link HarnessOrchestrator} calls the {@code ask} CLI command uses.
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

        if (hasContent(request.path("tools"))) {
            if (request.path("stream").asBoolean(false)) {
                GatewayErrors.send(exchange, mapper, 400, "invalid_request_error",
                        "Combining stream=true with client-supplied 'tools' is not yet supported by this CastCLI gateway build.");
                return;
            }
            handleWithClientTools(exchange, request);
            return;
        }

        String prompt;
        List<ChatMessage> history;
        try {
            JsonNode messagesNode = request.path("messages");
            if (ClientToolSupport.endsWithUserMessage(messagesNode)) {
                ClientToolSupport.ConversationSplit split = ClientToolSupport.splitLastUserTurn(messagesNode);
                history = split.history();
                prompt = split.currentUserText();
            } else {
                history = List.of();
                prompt = flattenMessages(messagesNode);
            }
        } catch (IllegalArgumentException e) {
            GatewayErrors.send(exchange, mapper, 400, "invalid_request_error", e.getMessage());
            return;
        }

        if (request.path("stream").asBoolean(false)) {
            handleStreaming(exchange, request, prompt, history);
        } else {
            handleNonStreaming(exchange, prompt, history);
        }
    }

    private void handleNonStreaming(HttpExchange exchange, String prompt, List<ChatMessage> history) throws IOException {
        try {
            TaskRequest task = new TaskRequest(prompt, Workload.AUTO, null);
            HarnessOrchestrator.Outcome outcome = orchestrator.run(task, history);
            sendCompletion(exchange, outcome);
        } catch (RuntimeException e) {
            ErrorMapping mapping = mapException(e);
            GatewayErrors.send(exchange, mapper, mapping.status(), mapping.type(), mapping.message());
        }
    }

    /**
     * Handles a request that supplies client-side {@code tools}: the model may either answer with
     * text or request tool calls, and either way the result is handed straight back to the client
     * unexecuted -- see {@link HarnessOrchestrator#runWithClientTools} for why this is a distinct
     * control flow from CastCLI's own server-side tools.
     */
    private void handleWithClientTools(HttpExchange exchange, JsonNode request) throws IOException {
        List<ChatMessage> messages;
        List<ToolSpecification> tools;
        ClientToolSupport.ToolChoiceResolution resolution;
        try {
            messages = ClientToolSupport.toChatMessages(request.path("messages"));
            tools = ClientToolSupport.parseToolSpecifications(request.path("tools"));
            resolution = ClientToolSupport.resolveToolChoice(request.path("tool_choice"), tools);
        } catch (IllegalArgumentException e) {
            GatewayErrors.send(exchange, mapper, 400, "invalid_request_error", e.getMessage());
            return;
        }

        try {
            TaskRequest task = new TaskRequest(routingPrompt(request.path("messages")), Workload.AUTO, null);
            HarnessOrchestrator.ClientToolOutcome outcome =
                    orchestrator.runWithClientTools(task, messages, resolution.tools(), resolution.toolChoice());
            sendClientToolCompletion(exchange, outcome);
        } catch (RuntimeException e) {
            ErrorMapping mapping = mapException(e);
            GatewayErrors.send(exchange, mapper, mapping.status(), mapping.type(), mapping.message());
        }
    }

    /**
     * Builds a best-effort prompt for routing signals/telemetry only -- never sent to the model
     * (the reconstructed {@link ChatMessage} list is). Unlike {@link #flattenMessages}, this must
     * tolerate the non-textual content that assistant tool-call and tool-result messages carry,
     * since a real tool-calling conversation's later turns are full of exactly that.
     */
    private static String routingPrompt(JsonNode messages) {
        StringBuilder prompt = new StringBuilder();
        for (JsonNode message : messages) {
            JsonNode content = message.path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                if (!prompt.isEmpty()) {
                    prompt.append("\n\n");
                }
                prompt.append(message.path("role").asText("user")).append(": ").append(content.asText());
            }
        }
        return prompt.isEmpty() ? "[tool call conversation]" : prompt.toString();
    }

    private void sendClientToolCompletion(HttpExchange exchange, HarnessOrchestrator.ClientToolOutcome outcome)
            throws IOException {
        ObjectNode response = mapper.createObjectNode();
        response.put("id", "chatcmpl-" + UUID.randomUUID());
        response.put("object", "chat.completion");
        response.put("created", Instant.now().getEpochSecond());
        response.put("model", outcome.provider().modelName());

        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (outcome.toolCalls().isEmpty()) {
            message.put("content", outcome.answer());
        } else {
            message.putNull("content");
            ArrayNode toolCalls = message.putArray("tool_calls");
            for (ToolExecutionRequest toolCall : outcome.toolCalls()) {
                ObjectNode toolCallNode = toolCalls.addObject();
                toolCallNode.put("id", toolCall.id());
                toolCallNode.put("type", "function");
                ObjectNode function = toolCallNode.putObject("function");
                function.put("name", toolCall.name());
                function.put("arguments", toolCall.arguments());
            }
        }
        choice.put("finish_reason", outcome.finishReason());

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

    /**
     * Streams the response as OpenAI {@code chat.completion.chunk} SSE events. Response headers are
     * committed lazily, on the first byte we actually have to send: if the orchestrator fails before
     * any token arrives, we can still answer with a normal HTTP error status. Once headers are
     * committed, a later failure can only be surfaced as a terminal SSE event, not an HTTP status
     * change -- that is an inherent constraint of streaming, not something a bigger try/catch fixes.
     */
    private void handleStreaming(HttpExchange exchange, JsonNode request, String prompt, List<ChatMessage> history)
            throws IOException {
        String id = "chatcmpl-" + UUID.randomUUID();
        String requestedModel = request.path("model").asText("castcli");
        boolean includeUsage = request.path("stream_options").path("include_usage").asBoolean(false);

        AtomicBoolean headersSent = new AtomicBoolean(false);
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        AtomicBoolean firstChunk = new AtomicBoolean(true);
        OutputStream[] bodyHolder = new OutputStream[1];

        java.util.function.Consumer<String> onToken = token -> {
            if (clientDisconnected.get()) {
                return;
            }
            // Per-chunk filtering only catches patterns that fit inside a single token; a secret
            // split across two streamed chunks slips through. This is a known, documented gap, not
            // full coverage -- see HarnessOrchestrator.streamWithProvider for the equivalent caveat
            // on the aggregated (non-streaming) path.
            String filtered = GuardrailFilter.filter(token);
            try {
                OutputStream body = ensureHeadersSent(exchange, headersSent, bodyHolder);
                boolean isFirst = firstChunk.compareAndSet(true, false);
                writeSseEvent(body, deltaChunk(id, requestedModel, filtered, isFirst));
            } catch (IOException e) {
                clientDisconnected.set(true);
            }
        };

        HarnessOrchestrator.Outcome outcome;
        try {
            TaskRequest task = new TaskRequest(prompt, Workload.AUTO, null);
            outcome = orchestrator.runStreaming(task, onToken, clientDisconnected::get, history);
        } catch (RuntimeException e) {
            if (!headersSent.get()) {
                ErrorMapping mapping = mapException(e);
                GatewayErrors.send(exchange, mapper, mapping.status(), mapping.type(), mapping.message());
                return;
            }
            try {
                writeSseEvent(bodyHolder[0], errorEvent(mapException(e)));
                writeDone(bodyHolder[0]);
            } catch (IOException ignored) {
                // Client is already gone; nothing left to notify.
            } finally {
                closeQuietly(bodyHolder[0]);
            }
            return;
        }

        if (clientDisconnected.get()) {
            closeQuietly(bodyHolder[0]);
            return;
        }

        try {
            OutputStream body = ensureHeadersSent(exchange, headersSent, bodyHolder);
            writeSseEvent(body, finishChunk(id, outcome.provider().modelName()));
            if (includeUsage) {
                writeSseEvent(body, usageChunk(id, outcome.provider().modelName(), outcome));
            }
            writeDone(body);
        } catch (IOException ignored) {
            // Client disconnected between the last token and the trailer chunks; nothing to do.
        } finally {
            closeQuietly(bodyHolder[0]);
        }
    }

    private static OutputStream ensureHeadersSent(HttpExchange exchange, AtomicBoolean headersSent,
                                                    OutputStream[] bodyHolder) throws IOException {
        if (headersSent.compareAndSet(false, true)) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            bodyHolder[0] = exchange.getResponseBody();
        }
        return bodyHolder[0];
    }

    private void writeSseEvent(OutputStream body, ObjectNode event) throws IOException {
        byte[] bytes = ("data: " + mapper.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8);
        body.write(bytes);
        body.flush();
    }

    private static void writeDone(OutputStream body) throws IOException {
        body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    private static void closeQuietly(OutputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // Client already disconnected.
        }
    }

    private ObjectNode deltaChunk(String id, String model, String content, boolean includeRole) {
        ObjectNode chunk = baseChunk(id, model);
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        if (includeRole) {
            delta.put("role", "assistant");
        }
        delta.put("content", content);
        choice.putNull("finish_reason");
        return chunk;
    }

    private ObjectNode finishChunk(String id, String model) {
        ObjectNode chunk = baseChunk(id, model);
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", "stop");
        return chunk;
    }

    private ObjectNode usageChunk(String id, String model, HarnessOrchestrator.Outcome outcome) {
        ObjectNode chunk = baseChunk(id, model);
        chunk.putArray("choices");
        ObjectNode usage = chunk.putObject("usage");
        usage.put("prompt_tokens", outcome.inputTokens());
        usage.put("completion_tokens", outcome.outputTokens());
        usage.put("total_tokens", outcome.inputTokens() + outcome.outputTokens());
        return chunk;
    }

    private ObjectNode errorEvent(ErrorMapping mapping) {
        ObjectNode event = mapper.createObjectNode();
        ObjectNode error = event.putObject("error");
        error.put("message", mapping.message());
        error.put("type", mapping.type());
        error.put("code", mapping.status());
        return event;
    }

    private ObjectNode baseChunk(String id, String model) {
        ObjectNode chunk = mapper.createObjectNode();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", Instant.now().getEpochSecond());
        chunk.put("model", model);
        return chunk;
    }

    private record ErrorMapping(int status, String type, String message) {
    }

    private static ErrorMapping mapException(RuntimeException e) {
        if (e instanceof BudgetExceededException) {
            return new ErrorMapping(429, "budget_exceeded", e.getMessage());
        }
        if (e instanceof ProviderExecutionException) {
            return new ErrorMapping(502, "provider_error", e.getMessage());
        }
        if (e instanceof IllegalArgumentException) {
            return new ErrorMapping(400, "invalid_request_error", e.getMessage());
        }
        if (e instanceof IllegalStateException) {
            return new ErrorMapping(503, "no_provider_available", e.getMessage());
        }
        return new ErrorMapping(500, "internal_error", "The gateway failed to complete this request.");
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
