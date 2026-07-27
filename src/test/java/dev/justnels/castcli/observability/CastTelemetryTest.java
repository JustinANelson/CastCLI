package dev.justnels.castcli.observability;

import dev.justnels.castcli.config.ObservabilityConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CastTelemetryTest {

    @Test
    void testPrometheusMetricsExport() {
        ObservabilityConfig config = new ObservabilityConfig(
                true, "test-service", false, "http://localhost:4317",
                ".cast/test-traces.jsonl", 5, 1.0, true, true, Map.of(), Map.of()
        );
        CastTelemetry telemetry = CastTelemetry.initialize(config, Path.of("."));

        var attrs = telemetry.attributes().build();
        telemetry.request(attrs);
        telemetry.toolCall(attrs);
        telemetry.modelUsage(100, 50, 0.001, 200, attrs);

        String prometheus = telemetry.exportPrometheusMetrics();

        assertTrue(prometheus.contains("castcli_requests_total 1"), "Should contain request count");
        assertTrue(prometheus.contains("castcli_tool_calls_total 1"), "Should contain tool call count");
        assertTrue(prometheus.contains("castcli_tokens_total 150"), "Should contain token total count");
    }
}
