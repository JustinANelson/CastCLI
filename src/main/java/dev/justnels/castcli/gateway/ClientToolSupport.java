package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts the OpenAI {@code tools}/{@code tool_choice}/{@code messages} request shapes into the
 * langchain4j types {@link HarnessOrchestrator#runWithClientTools} needs. Kept separate from
 * {@code ChatCompletionsHandler} so the conversion logic (the part most likely to have edge cases)
 * is directly unit-testable without an HTTP round trip.
 */
final class ClientToolSupport {

    private ClientToolSupport() {
    }

    /**
     * Builds langchain4j {@link ToolSpecification}s from an OpenAI {@code tools[]} array. Each
     * property's schema is passed through verbatim via {@link JsonRawSchema} rather than mapped into
     * langchain4j's structured schema hierarchy -- this preserves arbitrarily nested/complex JSON
     * Schema (enums, arrays, nested objects) without reimplementing a JSON Schema parser, at the cost
     * of CastCLI never inspecting a tool's parameter shape itself. Verified against a real
     * OpenAI-compatible wire request in {@code JsonRawSchemaWireVerificationTest}.
     */
    static List<ToolSpecification> parseToolSpecifications(JsonNode tools) {
        List<ToolSpecification> specifications = new ArrayList<>();
        for (JsonNode tool : tools) {
            String type = tool.path("type").asText("function");
            if (!"function".equals(type)) {
                throw new IllegalArgumentException("Unsupported tool type '" + type + "'; only 'function' is supported.");
            }
            JsonNode function = tool.path("function");
            String name = function.path("name").asText(null);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Each tool.function must have a non-blank 'name'.");
            }
            ToolSpecification.Builder builder = ToolSpecification.builder()
                    .name(name)
                    .parameters(parseParameterSchema(function.path("parameters")));
            if (function.hasNonNull("description")) {
                builder.description(function.path("description").asText());
            }
            specifications.add(builder.build());
        }
        return specifications;
    }

    private static JsonObjectSchema parseParameterSchema(JsonNode parameters) {
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        if (parameters.hasNonNull("description")) {
            schema.description(parameters.path("description").asText());
        }
        JsonNode properties = parameters.path("properties");
        if (properties.isObject()) {
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                schema.addProperty(property.getKey(), JsonRawSchema.from(property.getValue().toString()));
            }
        }
        JsonNode required = parameters.path("required");
        if (required.isArray()) {
            List<String> requiredNames = new ArrayList<>();
            required.forEach(node -> requiredNames.add(node.asText()));
            schema.required(requiredNames);
        }
        JsonNode additionalProperties = parameters.path("additionalProperties");
        if (additionalProperties.isBoolean()) {
            schema.additionalProperties(additionalProperties.asBoolean());
        }
        return schema.build();
    }

    /** Which tools to offer and how strongly to require a call, after resolving {@code tool_choice}. */
    record ToolChoiceResolution(List<ToolSpecification> tools, ToolChoice toolChoice) {
    }

    /**
     * Resolves OpenAI {@code tool_choice}: {@code "auto"}/{@code "required"}/{@code "none"} map
     * directly onto langchain4j's {@link ToolChoice}; a forced named function
     * ({@code {"type":"function","function":{"name":...}}}) is emulated by narrowing the tool list to
     * just that one tool and requiring a call, since langchain4j's {@code ToolChoice} has no
     * per-function forcing of its own.
     */
    static ToolChoiceResolution resolveToolChoice(JsonNode toolChoice, List<ToolSpecification> tools) {
        if (toolChoice.isMissingNode() || toolChoice.isNull()) {
            return new ToolChoiceResolution(tools, null);
        }
        if (toolChoice.isTextual()) {
            return switch (toolChoice.asText()) {
                case "auto" -> new ToolChoiceResolution(tools, ToolChoice.AUTO);
                case "required" -> new ToolChoiceResolution(tools, ToolChoice.REQUIRED);
                case "none" -> new ToolChoiceResolution(tools, ToolChoice.NONE);
                default -> throw new IllegalArgumentException("Unsupported tool_choice value: " + toolChoice.asText());
            };
        }
        if (toolChoice.isObject() && "function".equals(toolChoice.path("type").asText())) {
            String forcedName = toolChoice.path("function").path("name").asText(null);
            if (forcedName == null || forcedName.isBlank()) {
                throw new IllegalArgumentException("tool_choice.function.name is required when forcing a specific tool.");
            }
            List<ToolSpecification> forced = tools.stream().filter(tool -> tool.name().equals(forcedName)).toList();
            if (forced.isEmpty()) {
                throw new IllegalArgumentException("tool_choice names a tool not present in 'tools': " + forcedName);
            }
            return new ToolChoiceResolution(forced, ToolChoice.REQUIRED);
        }
        throw new IllegalArgumentException("Unsupported tool_choice shape.");
    }

    /**
     * Reconstructs a full langchain4j message list from an OpenAI {@code messages[]} array,
     * including assistant tool-call requests and {@code role:"tool"} results -- unlike the
     * string-flattening path used when no tools are involved, tool-call round trips require the real
     * message structure to survive across turns.
     */
    static List<ChatMessage> toChatMessages(JsonNode messages) {
        if (!messages.isArray() || messages.isEmpty()) {
            throw new IllegalArgumentException("'messages' must be a non-empty array.");
        }
        List<ChatMessage> result = new ArrayList<>();
        for (JsonNode message : messages) {
            String role = message.path("role").asText("user");
            switch (role) {
                case "system" -> result.add(SystemMessage.from(requireTextContent(message)));
                case "user" -> result.add(UserMessage.from(requireTextContent(message)));
                case "assistant" -> result.add(toAssistantMessage(message));
                case "tool" -> result.add(toToolResultMessage(message));
                default -> throw new IllegalArgumentException("Unsupported message role: " + role);
            }
        }
        return result;
    }

    private static ChatMessage toAssistantMessage(JsonNode message) {
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return AiMessage.from(requireTextContent(message));
        }
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (JsonNode toolCall : toolCalls) {
            requests.add(ToolExecutionRequest.builder()
                    .id(toolCall.path("id").asText(null))
                    .name(toolCall.path("function").path("name").asText(null))
                    .arguments(toolCall.path("function").path("arguments").asText("{}"))
                    .build());
        }
        JsonNode content = message.path("content");
        return content.isTextual() ? AiMessage.from(content.asText(), requests) : AiMessage.from(requests);
    }

    private static ChatMessage toToolResultMessage(JsonNode message) {
        String toolCallId = message.path("tool_call_id").asText(null);
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("'tool' role messages require a non-blank 'tool_call_id'.");
        }
        return ToolExecutionResultMessage.from(toolCallId, "", requireTextContent(message));
    }

    private static String requireTextContent(JsonNode message) {
        JsonNode content = message.path("content");
        if (!content.isTextual()) {
            throw new IllegalArgumentException(
                    "Only string 'content' is supported in this CastCLI gateway build (message parts arrays are not).");
        }
        return content.asText();
    }
}
