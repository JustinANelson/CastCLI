package dev.justnels.castcli.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cross-process-safe JSONL trace archive for debugging and deterministic run comparison. */
public final class JsonlSpanExporter implements SpanExporter {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonlSpanExporter(Path path) {
        this.path = path.toAbsolutePath().normalize();
        try {
            if (this.path.getParent() != null) Files.createDirectories(this.path.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create trace directory", e);
        }
    }

    @Override
    public synchronized CompletableResultCode export(Collection<SpanData> spans) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND); var lock = channel.lock()) {
            if (!lock.isValid()) return CompletableResultCode.ofFailure();
            for (SpanData span : spans) {
                byte[] line = (mapper.writeValueAsString(toMap(span)) + "\n").getBytes(StandardCharsets.UTF_8);
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            channel.force(false);
            return CompletableResultCode.ofSuccess();
        } catch (Exception e) {
            return CompletableResultCode.ofFailure();
        }
    }

    @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
    @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }

    private static Map<String, Object> toMap(SpanData span) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", span.getTraceId());
        result.put("spanId", span.getSpanId());
        result.put("parentSpanId", span.getParentSpanContext().isValid() ? span.getParentSpanId() : null);
        result.put("name", span.getName());
        result.put("kind", span.getKind().name());
        result.put("startEpochNanos", span.getStartEpochNanos());
        result.put("endEpochNanos", span.getEndEpochNanos());
        result.put("durationNanos", span.getEndEpochNanos() - span.getStartEpochNanos());
        result.put("status", span.getStatus().getStatusCode().name());
        result.put("statusDescription", span.getStatus().getDescription());
        Map<String, Object> resourceAttributes = new LinkedHashMap<>();
        span.getResource().getAttributes().forEach(
                (key, value) -> resourceAttributes.put(key.getKey(), normalize(value)));
        result.put("resourceAttributes", resourceAttributes);
        Map<String, Object> attributes = new LinkedHashMap<>();
        span.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), normalize(value)));
        result.put("attributes", attributes);
        List<Map<String, Object>> events = new ArrayList<>();
        for (EventData event : span.getEvents()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", event.getName());
            item.put("epochNanos", event.getEpochNanos());
            Map<String, Object> eventAttributes = new LinkedHashMap<>();
            event.getAttributes().forEach((key, value) -> eventAttributes.put(key.getKey(), normalize(value)));
            item.put("attributes", eventAttributes);
            events.add(item);
        }
        result.put("events", events);
        return result;
    }

    private static Object normalize(Object value) {
        if (value instanceof Collection<?> collection) return collection.stream().map(JsonlSpanExporter::normalize).toList();
        return value;
    }
}
