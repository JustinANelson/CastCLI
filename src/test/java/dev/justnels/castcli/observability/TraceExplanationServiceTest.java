package dev.justnels.castcli.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceExplanationServiceTest {

    @Test
    void explainsTraceLog(@TempDir Path tempDir) throws IOException {
        Path traceFile = tempDir.resolve("spans.jsonl");
        String jsonLine = """
                {"traceId":"t-12345","spanId":"s-1","parentSpanId":null,"name":"castcli.request","kind":"INTERNAL","startEpochNanos":1000000,"endEpochNanos":5000000,"durationNanos":4000000,"status":"OK","attributes":{"provider.id":"local-qwen","model.name":"qwen2.5-coder","tokens.input":150,"tokens.output":42,"cost.usd":0.0},"events":[{"name":"tool_executed","epochNanos":2000000}]}
                """;
        Files.writeString(traceFile, jsonLine);

        TraceExplanationService service = new TraceExplanationService();
        TraceExplanationService.TraceExplanationReport report = service.explainTrace(traceFile, "t-12345");

        assertNotNull(report);
        assertEquals("t-12345", report.traceId());
        assertEquals(1, report.totalSpans());
        assertEquals("OK", report.status());
        assertTrue(report.narrative().contains("local-qwen"));
        assertTrue(report.narrative().contains("qwen2.5-coder"));
    }

    @Test
    void autoDetectsMostRecentTraceWhenTraceIdNull(@TempDir Path tempDir) throws IOException {
        Path traceFile = tempDir.resolve("spans.jsonl");
        String lines = """
                {"traceId":"t-old","spanId":"s-1","name":"old.span","durationNanos":1000000,"status":"OK","attributes":{}}
                {"traceId":"t-new","spanId":"s-2","name":"new.span","durationNanos":2000000,"status":"OK","attributes":{}}
                """;
        Files.writeString(traceFile, lines);

        TraceExplanationService service = new TraceExplanationService();
        TraceExplanationService.TraceExplanationReport report = service.explainTrace(traceFile, null);

        assertNotNull(report);
        assertEquals("t-new", report.traceId());
        assertEquals(1, report.totalSpans());
    }

    @Test
    void throwsOnMissingFile(@TempDir Path tempDir) {
        TraceExplanationService service = new TraceExplanationService();
        Path missing = tempDir.resolve("missing.jsonl");
        assertThrows(IllegalArgumentException.class, () -> service.explainTrace(missing, "t-123"));
    }
}
