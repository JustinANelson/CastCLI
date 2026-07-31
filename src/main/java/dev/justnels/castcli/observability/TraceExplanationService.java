package dev.justnels.castcli.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses local trace archives (.cast/traces/spans.jsonl) and generates human-readable
 * narrative explanations for past CastCLI routing and execution runs.
 */
public final class TraceExplanationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record SpanSummary(
            String spanId,
            String parentSpanId,
            String name,
            String status,
            double durationMs,
            JsonNode attributes,
            List<String> events) {}

    public record TraceExplanationReport(
            String traceId,
            int totalSpans,
            double totalDurationMs,
            String status,
            List<SpanSummary> spans,
            String narrative) {}

    public TraceExplanationReport explainTrace(Path traceFile, String targetTraceId) throws IOException {
        if (!Files.isRegularFile(traceFile)) {
            throw new IllegalArgumentException("Trace archive file not found at " + traceFile);
        }

        List<JsonNode> matchingNodes = new ArrayList<>();
        List<String> lines = Files.readAllLines(traceFile);

        String resolvedTraceId = targetTraceId;
        if (resolvedTraceId == null || resolvedTraceId.isBlank()) {
            // Find most recent trace ID from the end of the file
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    try {
                        JsonNode node = MAPPER.readTree(line);
                        if (node.has("traceId") && !node.get("traceId").asText().isBlank()) {
                            resolvedTraceId = node.get("traceId").asText();
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (resolvedTraceId == null) {
            throw new IllegalArgumentException("No valid trace spans found in " + traceFile);
        }

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                if (node.has("traceId") && resolvedTraceId.equalsIgnoreCase(node.get("traceId").asText())) {
                    matchingNodes.add(node);
                }
            } catch (Exception ignored) {
            }
        }

        if (matchingNodes.isEmpty()) {
            throw new IllegalArgumentException("Trace ID '" + resolvedTraceId + "' was not found in " + traceFile);
        }

        List<SpanSummary> spanSummaries = new ArrayList<>();
        double maxDurationMs = 0.0;
        String overallStatus = "OK";

        StringBuilder narrative = new StringBuilder();
        narrative.append("Execution Trace Explanation for Trace ID: ").append(resolvedTraceId).append("\n");
        narrative.append("======================================================================\n");

        for (JsonNode node : matchingNodes) {
            String spanId = node.path("spanId").asText();
            String parentSpanId = node.path("parentSpanId").asText(null);
            String name = node.path("name").asText("unknown");
            String status = node.path("status").asText("OK");
            long durationNanos = node.path("durationNanos").asLong(0);
            double durationMs = durationNanos / 1_000_000.0;
            if (durationMs > maxDurationMs) {
                maxDurationMs = durationMs;
            }
            if ("ERROR".equalsIgnoreCase(status)) {
                overallStatus = "ERROR";
            }

            JsonNode attributes = node.path("attributes");
            List<String> eventNames = new ArrayList<>();
            if (node.has("events") && node.get("events").isArray()) {
                for (JsonNode event : node.get("events")) {
                    eventNames.add(event.path("name").asText());
                }
            }

            spanSummaries.add(new SpanSummary(spanId, parentSpanId, name, status, durationMs, attributes, eventNames));

            narrative.append(String.format("- Span: %-30s | status=%-5s | duration=%.2f ms%n", name, status, durationMs));
            if (attributes.has("provider.id")) {
                narrative.append("    Model Provider: ").append(attributes.get("provider.id").asText()).append("\n");
            }
            if (attributes.has("model.name")) {
                narrative.append("    Model Name:     ").append(attributes.get("model.name").asText()).append("\n");
            }
            if (attributes.has("tokens.input") || attributes.has("tokens.output")) {
                narrative.append("    Tokens:         in=")
                        .append(attributes.path("tokens.input").asInt(0))
                        .append(", out=")
                        .append(attributes.path("tokens.output").asInt(0))
                        .append("\n");
            }
            if (attributes.has("cost.usd")) {
                narrative.append("    Est. Cost:      $").append(attributes.get("cost.usd").asText()).append("\n");
            }
            if (!eventNames.isEmpty()) {
                narrative.append("    Events:         ").append(String.join(", ", eventNames)).append("\n");
            }
        }

        narrative.append("\nSummary:\n");
        narrative.append(String.format("  Total Spans:    %d%n", spanSummaries.size()));
        narrative.append(String.format("  Total Duration: %.2f ms%n", maxDurationMs));
        narrative.append(String.format("  Overall Status: %s%n", overallStatus));

        return new TraceExplanationReport(
                resolvedTraceId,
                spanSummaries.size(),
                maxDurationMs,
                overallStatus,
                spanSummaries,
                narrative.toString());
    }
}
