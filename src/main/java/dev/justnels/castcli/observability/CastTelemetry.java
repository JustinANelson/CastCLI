package dev.justnels.castcli.observability;

import dev.justnels.castcli.config.ObservabilityConfig;
import dev.justnels.castcli.doctor.BuildInfo;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Vendor-neutral telemetry facade used throughout CastCLI. */
public final class CastTelemetry implements AutoCloseable {
    private static final Object LOCK = new Object();
    private static volatile CastTelemetry current = noop();
    private static volatile ObservabilityConfig currentConfig = ObservabilityConfig.disabled();
    private static volatile Path currentWorkspace = Path.of(".").toAbsolutePath().normalize();

    private final ObservabilityConfig config;
    private final OpenTelemetry openTelemetry;
    private final OpenTelemetrySdk sdk;
    private final Tracer tracer;
    private final LongCounter requests;
    private final LongCounter failures;
    private final LongCounter retries;
    private final LongCounter tokens;
    private final LongCounter toolCalls;
    private final LongCounter memoryOperations;
    private final LongCounter approvalDecisions;
    private final DoubleHistogram duration;
    private final DoubleHistogram cost;

    private final java.util.concurrent.atomic.LongAdder requestsCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder failuresCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder retriesCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder tokensCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder toolCallsCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder memoryOpsCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder approvalDecisionsCount = new java.util.concurrent.atomic.LongAdder();

    private CastTelemetry(ObservabilityConfig config, OpenTelemetry openTelemetry, OpenTelemetrySdk sdk) {
        this.config = config;
        this.openTelemetry = openTelemetry;
        this.sdk = sdk;
        this.tracer = openTelemetry.getTracer("dev.justnels.castcli", BuildInfo.version());
        var meter = openTelemetry.getMeter("dev.justnels.castcli");
        this.requests = meter.counterBuilder("castcli.requests").setDescription("CastCLI requests").build();
        this.failures = meter.counterBuilder("castcli.failures").setDescription("Failed operations").build();
        this.retries = meter.counterBuilder("castcli.retries").setDescription("Provider retries").build();
        this.tokens = meter.counterBuilder("gen_ai.client.token.usage").setDescription("LLM token usage").build();
        this.toolCalls = meter.counterBuilder("castcli.tool.calls").setDescription("Tool executions").build();
        this.memoryOperations = meter.counterBuilder("castcli.memory.operations").setDescription("Memory operations").build();
        this.approvalDecisions = meter.counterBuilder("castcli.approval.decisions").setDescription("Approval decisions").build();
        this.duration = meter.histogramBuilder("gen_ai.client.operation.duration").setUnit("s").build();
        this.cost = meter.histogramBuilder("castcli.estimated.cost").setUnit("USD").build();
    }

    public static CastTelemetry initialize(ObservabilityConfig config, Path workspaceRoot) {
        ObservabilityConfig effective = config == null ? ObservabilityConfig.disabled() : config;
        Path workspace = workspaceRoot.toAbsolutePath().normalize();
        synchronized (LOCK) {
            if (effective.equals(currentConfig) && workspace.equals(currentWorkspace)) return current;
            current.close();
            current = effective.enabled() ? create(effective, workspace) : noop();
            currentConfig = effective;
            currentWorkspace = workspace;
            return current;
        }
    }

    public static CastTelemetry current() { return current; }

    public SpanScope span(String name) {
        Span span = tracer.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan();
        return new SpanScope(span, span.makeCurrent());
    }

    public AttributesBuilder attributes() { return Attributes.builder(); }
    public void request(Attributes attributes) { requestsCount.increment(); requests.add(1, attributes); }
    public void failure(Attributes attributes) { failuresCount.increment(); failures.add(1, attributes); }
    public void retry(Attributes attributes) { retriesCount.increment(); retries.add(1, attributes); }
    public void toolCall(Attributes attributes) { toolCallsCount.increment(); toolCalls.add(1, attributes); }
    public void memoryOperation(Attributes attributes) { memoryOpsCount.increment(); memoryOperations.add(1, attributes); }
    public void approval(boolean approved, Attributes attributes) {
        approvalDecisionsCount.increment();
        approvalDecisions.add(1, attributes.toBuilder().put("castcli.approval.approved", approved).build());
    }
    public void modelUsage(long inputTokens, long outputTokens, double costUsd, long durationMs, Attributes attributes) {
        tokensCount.add(inputTokens + outputTokens);
        tokens.add(inputTokens, attributes.toBuilder().put("gen_ai.token.type", "input").build());
        tokens.add(outputTokens, attributes.toBuilder().put("gen_ai.token.type", "output").build());
        duration.record(durationMs / 1_000.0, attributes);
        cost.record(costUsd, attributes);
    }

    public String exportPrometheusMetrics() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP castcli_requests_total Total CastCLI requests\n")
          .append("# TYPE castcli_requests_total counter\n")
          .append("castcli_requests_total ").append(requestsCount.sum()).append("\n\n");
        sb.append("# HELP castcli_failures_total Total failed operations\n")
          .append("# TYPE castcli_failures_total counter\n")
          .append("castcli_failures_total ").append(failuresCount.sum()).append("\n\n");
        sb.append("# HELP castcli_retries_total Total provider retries\n")
          .append("# TYPE castcli_retries_total counter\n")
          .append("castcli_retries_total ").append(retriesCount.sum()).append("\n\n");
        sb.append("# HELP castcli_tokens_total Total LLM token usage\n")
          .append("# TYPE castcli_tokens_total counter\n")
          .append("castcli_tokens_total ").append(tokensCount.sum()).append("\n\n");
        sb.append("# HELP castcli_tool_calls_total Total tool executions\n")
          .append("# TYPE castcli_tool_calls_total counter\n")
          .append("castcli_tool_calls_total ").append(toolCallsCount.sum()).append("\n\n");
        sb.append("# HELP castcli_memory_operations_total Total memory operations\n")
          .append("# TYPE castcli_memory_operations_total counter\n")
          .append("castcli_memory_operations_total ").append(memoryOpsCount.sum()).append("\n\n");
        sb.append("# HELP castcli_approval_decisions_total Total approval decisions\n")
          .append("# TYPE castcli_approval_decisions_total counter\n")
          .append("castcli_approval_decisions_total ").append(approvalDecisionsCount.sum()).append("\n");
        return sb.toString();
    }

    public String promptHash(String prompt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(prompt.hashCode());
        }
    }

    public void annotatePrompt(SpanScope span, String prompt) {
        span.attribute("gen_ai.input.prompt.sha256", promptHash(prompt));
        span.attribute("gen_ai.input.prompt.length", prompt.length());
        if (config.capturePrompts()) span.attribute("gen_ai.input.prompt", SecretRedactor.redact(prompt));
    }

    public String forceFlush() {
        if (sdk == null) return "telemetry disabled";
        var traceResult = sdk.getSdkTracerProvider().forceFlush();
        traceResult.join(10, java.util.concurrent.TimeUnit.SECONDS);
        var metricResult = sdk.getSdkMeterProvider().forceFlush();
        metricResult.join(10, java.util.concurrent.TimeUnit.SECONDS);
        boolean traces = traceResult.isSuccess();
        boolean metrics = metricResult.isSuccess();
        return traces && metrics ? "telemetry flushed" : "telemetry flush incomplete";
    }

    @Override public void close() { if (sdk != null) sdk.close(); }

    private static CastTelemetry create(ObservabilityConfig config, Path workspace) {
        AttributesBuilder resourceAttributes = Attributes.builder().put("service.name", config.serviceName())
                .put("service.version", BuildInfo.version());
        config.resourceAttributes().forEach(resourceAttributes::put);
        Resource resource = Resource.getDefault().merge(Resource.create(resourceAttributes.build()));

        List<SpanExporter> exporters = new ArrayList<>();
        if (config.jsonlPath() != null && !config.jsonlPath().isBlank()) {
            Path configured = Path.of(config.jsonlPath());
            exporters.add(new JsonlSpanExporter(configured.isAbsolute() ? configured : workspace.resolve(configured)));
        }
        if (config.otlpEnabled()) {
            var exporter = OtlpGrpcSpanExporter.builder().setEndpoint(config.otlpEndpoint());
            config.otlpHeaders().forEach(exporter::addHeader);
            exporters.add(exporter.build());
        }

        SdkTracerProviderBuilder traceBuilder = SdkTracerProvider.builder().setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(config.sampleProbability())));
        if (!exporters.isEmpty()) traceBuilder.addSpanProcessor(BatchSpanProcessor.builder(SpanExporter.composite(exporters))
                .setScheduleDelay(Duration.ofSeconds(config.exportIntervalSeconds())).build());
        SdkTracerProvider tracerProvider = traceBuilder.build();

        SdkMeterProviderBuilder meterBuilder = SdkMeterProvider.builder().setResource(resource);
        if (config.metricsEnabled() && config.otlpEnabled()) {
            var metricExporterBuilder = OtlpGrpcMetricExporter.builder().setEndpoint(config.otlpEndpoint());
            config.otlpHeaders().forEach(metricExporterBuilder::addHeader);
            var metricExporter = metricExporterBuilder.build();
            meterBuilder.registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                    .setInterval(Duration.ofSeconds(config.exportIntervalSeconds())).build());
        }
        SdkMeterProvider meterProvider = meterBuilder.build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).setMeterProvider(meterProvider).build();
        return new CastTelemetry(config, sdk, sdk);
    }

    private static CastTelemetry noop() { return new CastTelemetry(ObservabilityConfig.disabled(), OpenTelemetry.noop(), null); }

    public static final class SpanScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private boolean failed;
        private SpanScope(Span span, Scope scope) { this.span = span; this.scope = scope; }
        public SpanScope attribute(String key, String value) { if (value != null) span.setAttribute(key, SecretRedactor.redact(value)); return this; }
        public SpanScope attribute(String key, long value) { span.setAttribute(key, value); return this; }
        public SpanScope attribute(String key, double value) { span.setAttribute(key, value); return this; }
        public SpanScope attribute(String key, boolean value) { span.setAttribute(key, value); return this; }
        public SpanScope attributes(Attributes attributes) { span.setAllAttributes(attributes); return this; }
        public SpanScope event(String name) { span.addEvent(name); return this; }
        public SpanScope event(String name, Attributes attributes) { span.addEvent(name, attributes); return this; }
        public SpanScope error(Throwable error) {
            failed = true; span.recordException(error); span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage())); return this;
        }
        public String traceId() { return span.getSpanContext().getTraceId(); }
        @Override public void close() { if (!failed) span.setStatus(StatusCode.OK); scope.close(); span.end(); }
    }
}
