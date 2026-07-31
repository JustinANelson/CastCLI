package dev.justnels.castcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A single tool exposed by {@link McpStdioServer}: a name, a JSON-schema for its arguments, and a handler. */
public record McpTool(String name, String description, ObjectNode inputSchema, Handler handler) {
    @FunctionalInterface
    public interface Handler {
        ExecutionResult handle(JsonNode arguments) throws Exception;
    }

    public record ExecutionResult(String text, Delegation delegation, boolean truncated) {
        public ExecutionResult(String text, Delegation delegation) {
            this(text, delegation, false);
        }

        public static ExecutionResult text(String text) {
            return new ExecutionResult(text, null, false);
        }
    }

    public record Delegation(
            String traceId,
            String providerId,
            String providerTier,
            String modelName,
            long inputTokens,
            long outputTokens,
            double estimatedCostUsd,
            String promptSha256,
            int promptChars,
            long modelDurationMs) {
    }
}

