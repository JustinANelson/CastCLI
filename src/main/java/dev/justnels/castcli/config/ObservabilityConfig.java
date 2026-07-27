package dev.justnels.castcli.config;

import java.util.Map;

/** OpenTelemetry and local reproducibility-trace configuration. */
public record ObservabilityConfig(
        boolean enabled,
        String serviceName,
        boolean otlpEnabled,
        String otlpEndpoint,
        String jsonlPath,
        int exportIntervalSeconds,
        double sampleProbability,
        boolean capturePrompts,
        boolean metricsEnabled,
        Map<String, String> otlpHeaders,
        Map<String, String> resourceAttributes) {

    public ObservabilityConfig {
        serviceName = textOrDefault(serviceName, "cast-cli");
        otlpEndpoint = textOrDefault(otlpEndpoint, "http://localhost:4317");
        jsonlPath = textOrDefault(jsonlPath, ".cast/traces/spans.jsonl");
        if (exportIntervalSeconds < 1) exportIntervalSeconds = 10;
        if (sampleProbability < 0 || sampleProbability > 1) sampleProbability = 1.0;
        otlpHeaders = otlpHeaders == null ? Map.of() : Map.copyOf(otlpHeaders);
        resourceAttributes = resourceAttributes == null ? Map.of() : Map.copyOf(resourceAttributes);
    }

    public static ObservabilityConfig disabled() {
        return new ObservabilityConfig(false, null, false, null, null, 10, 1, false, true, Map.of(), Map.of());
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
