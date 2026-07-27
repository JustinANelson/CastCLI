package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.index.WorkspaceEmbeddingIndex;
import dev.justnels.castcli.model.ChatModelFactory;
import dev.justnels.castcli.model.EmbeddingModelFactory;
import dev.justnels.castcli.observability.CastTelemetry;
import dev.justnels.castcli.memory.MemoryContextProvider;
import dev.justnels.castcli.memory.MemoryStore;
import dev.justnels.castcli.memory.SqliteMemoryStore;
import dev.justnels.castcli.reliability.ProviderHealthRegistry;
import dev.justnels.castcli.reliability.ReliabilityExecutor;
import dev.justnels.castcli.security.GuardrailFilter;
import dev.justnels.castcli.tools.ApprovalGate;
import dev.justnels.castcli.tools.DefaultToolSelector;
import dev.justnels.castcli.tools.DenyApprovalGate;
import dev.justnels.castcli.tools.FastPathExecutor;
import dev.justnels.castcli.tools.MemoryTools;
import dev.justnels.castcli.tools.SemanticSearchTools;
import dev.justnels.castcli.tools.ToolSelector;
import dev.justnels.castcli.tools.WorkspaceTools;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import io.opentelemetry.api.common.Attributes;

public class HarnessOrchestrator {
    public static final ProviderConfig FAST_PATH_PROVIDER = new ProviderConfig(
            "fast-path", ModelTier.SMALL_LOCAL, "internal", "deterministic-java", null, 0.0, 1, true, true);

    public record Outcome(
            ProviderConfig provider,
            String answer,
            List<String> toolsSelected,
            List<String> toolsUsed,
            long durationMs,
            boolean fastPath,
            long inputTokens,
            long outputTokens,
            double estimatedCostUsd,
            String traceId) {
        public Outcome(ProviderConfig provider, String answer, List<String> toolsSelected, List<String> toolsUsed,
                       long durationMs, boolean fastPath, long inputTokens, long outputTokens, double estimatedCostUsd) {
            this(provider, answer, toolsSelected, toolsUsed, durationMs, fastPath, inputTokens, outputTokens, estimatedCostUsd, null);
        }
        public Outcome(ProviderConfig provider, String answer, List<String> toolsUsed) {
            this(provider, answer, toolsUsed, toolsUsed, 0L, false, 0L, 0L, 0.0, null);
        }

        public Outcome(
                ProviderConfig provider,
                String answer,
                List<String> toolsSelected,
                List<String> toolsUsed,
                long durationMs,
                boolean fastPath) {
            this(provider, answer, toolsSelected, toolsUsed, durationMs, fastPath, 0L, 0L, 0.0, null);
        }

        Outcome withTraceId(String id) {
            return new Outcome(provider, answer, toolsSelected, toolsUsed, durationMs, fastPath,
                    inputTokens, outputTokens, estimatedCostUsd, id);
        }
    }

    interface Assistant {
        Result<String> chat(String prompt);
    }

    private final HarnessConfig config;
    private final ModelRouter router;
    private final ChatModelFactory modelFactory;
    private final ToolSelector toolSelector;
    private final FastPathExecutor fastPathExecutor;
    private final ApprovalGate approvalGate;
    private final ToolProvider mcpToolProvider;
    private final WorkspaceEmbeddingIndex embeddingIndex;
    private final ProviderHealthRegistry providerHealth;
    private final ReliabilityExecutor reliabilityExecutor;
    private final MemoryStore memoryStore;
    private final MemoryContextProvider memoryContextProvider;
    private final CastTelemetry telemetry;

    public HarnessOrchestrator(HarnessConfig config) {
        this(config, new ChatModelFactory(), new DefaultToolSelector(), new FastPathExecutor(), DenyApprovalGate.INSTANCE, null);
    }

    HarnessOrchestrator(HarnessConfig config, ChatModelFactory modelFactory) {
        this(config, modelFactory, new DefaultToolSelector(), new FastPathExecutor(), DenyApprovalGate.INSTANCE, null);
    }

    HarnessOrchestrator(
            HarnessConfig config,
            ChatModelFactory modelFactory,
            ToolSelector toolSelector,
            FastPathExecutor fastPathExecutor) {
        this(config, modelFactory, toolSelector, fastPathExecutor, DenyApprovalGate.INSTANCE, null);
    }

    public HarnessOrchestrator(
            HarnessConfig config,
            ChatModelFactory modelFactory,
            ToolSelector toolSelector,
            FastPathExecutor fastPathExecutor,
            ApprovalGate approvalGate,
            ToolProvider mcpToolProvider) {
        this.config = config;
        Path workspaceRoot = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
        this.telemetry = CastTelemetry.initialize(config.observability(), workspaceRoot);
        this.providerHealth = new ProviderHealthRegistry(config.reliability());
        this.router = new ModelRouter(config, new PolicyRoutingStrategy(), providerHealth);
        this.reliabilityExecutor = new ReliabilityExecutor(config.reliability(), providerHealth);
        this.modelFactory = modelFactory;
        this.toolSelector = toolSelector;
        this.fastPathExecutor = fastPathExecutor;
        this.approvalGate = approvalGate == null ? DenyApprovalGate.INSTANCE : approvalGate;
        this.mcpToolProvider = mcpToolProvider;
        this.embeddingIndex = config.embeddings().enabled()
                ? new WorkspaceEmbeddingIndex(config.embeddings(), Path.of(config.tools().workspaceRoot()),
                        new EmbeddingModelFactory().create(config.embeddings()))
                : null;
        if (config.memory().enabled()) {
            Path workspace = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
            Path configuredPath = Path.of(config.memory().databasePath());
            Path databasePath = configuredPath.isAbsolute() ? configuredPath : workspace.resolve(configuredPath);
            this.memoryStore = new SqliteMemoryStore(databasePath);
            dev.justnels.castcli.lifecycle.ShutdownHookManager.getInstance().register(this.memoryStore);
            this.memoryStore.purgeExpired();
            this.memoryStore.purgeOlderThan(config.memory().retentionDays());
            this.memoryContextProvider = new MemoryContextProvider(memoryStore, config.memory());
        } else {
            this.memoryStore = null;
            this.memoryContextProvider = null;
        }
    }

    public Outcome run(TaskRequest task) {
        Attributes requestAttributes = Attributes.builder()
                .put("gen_ai.operation.name", "chat")
                .put("castcli.workload", task.workload().name())
                .put("castcli.routing.strict", task.strict())
                .build();
        telemetry.request(requestAttributes);
        try (CastTelemetry.SpanScope span = telemetry.span("castcli.request").attributes(requestAttributes)) {
            telemetry.annotatePrompt(span, task.prompt());
            try {
                Outcome outcome = runCore(task);
                span.attribute("gen_ai.provider.name", outcome.provider().id())
                        .attribute("gen_ai.request.model", outcome.provider().modelName())
                        .attribute("gen_ai.usage.input_tokens", outcome.inputTokens())
                        .attribute("gen_ai.usage.output_tokens", outcome.outputTokens())
                        .attribute("castcli.estimated.cost.usd", outcome.estimatedCostUsd())
                        .attribute("castcli.fast_path", outcome.fastPath());
                telemetry.modelUsage(outcome.inputTokens(), outcome.outputTokens(), outcome.estimatedCostUsd(),
                        outcome.durationMs(), providerAttributes(outcome.provider()));
                for (String tool : outcome.toolsUsed()) {
                    span.event("tool.executed", Attributes.builder().put("gen_ai.tool.name", tool).build());
                    telemetry.toolCall(Attributes.builder().put("gen_ai.tool.name", tool).build());
                }
                return outcome.withTraceId(span.traceId());
            } catch (RuntimeException failure) {
                span.error(failure);
                telemetry.failure(requestAttributes);
                throw failure;
            }
        }
    }

    private Outcome runCore(TaskRequest task) {
        long startTime = System.currentTimeMillis();

        var fastPathResult = fastPathExecutor.executeIfPossible(task, config.tools());
        if (fastPathResult.isPresent()) {
            long duration = System.currentTimeMillis() - startTime;
            var res = fastPathResult.get();
            return new Outcome(
                    FAST_PATH_PROVIDER,
                    res.answer(),
                    List.of(res.toolUsed()),
                    List.of(res.toolUsed()),
                    duration,
                    true);
        }

        List<Object> selectedTools = new ArrayList<>(toolSelector.selectTools(task, config.tools(), approvalGate));
        if (embeddingIndex != null && selectedTools.stream().anyMatch(WorkspaceTools.class::isInstance)) {
            selectedTools.add(new SemanticSearchTools(embeddingIndex));
        }
        if (memoryStore != null && selectedTools.stream().anyMatch(WorkspaceTools.class::isInstance)) {
            selectedTools.add(new MemoryTools(memoryStore, config.memory().defaultNamespace()));
        }
        List<String> selectedToolNames = selectedTools.stream()
                .map(t -> t.getClass().getSimpleName())
                .toList();

        List<RoutingCandidate> ranked = router.rank(task, selectedTools);
        for (RoutingCandidate candidate : ranked) {
            io.opentelemetry.api.trace.Span.current().addEvent("routing.candidate", Attributes.builder()
                    .put("castcli.provider.id", candidate.provider().id())
                    .put("castcli.provider.tier", candidate.provider().tier().name())
                    .put("castcli.routing.score", candidate.score())
                    .put("castcli.routing.reasons", String.join(",", candidate.reasons()))
                    .build());
        }
        if (ranked.isEmpty()) return failNoProvider(task, selectedTools);
        List<ProviderConfig> candidates = orderedFallbacks(ranked);
        if (task.strict() || !selectedTools.isEmpty()) candidates = candidates.subList(0, 1);
        TaskRequest executionTask = memoryContextProvider == null ? task
                : new TaskRequest(memoryContextProvider.augment(task.prompt()), task.workload(), task.requestedTier(), task.strict());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.reliability().requestDeadlineSeconds());
        RuntimeException firstFailure = null;
        for (ProviderConfig provider : candidates) {
            try {
                boolean retrySafe = selectedTools.isEmpty();
                return reliabilityExecutor.execute(provider,
                        () -> executeWithProvider(executionTask, provider, selectedTools, selectedToolNames, startTime),
                        retrySafe, deadline);
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                io.opentelemetry.api.trace.Span.current().addEvent("provider.fallback", Attributes.builder()
                        .put("castcli.provider.id", provider.id())
                        .put("exception.type", failure.getClass().getName()).build());
                if (task.strict() || System.nanoTime() >= deadline) break;
            }
        }
        throw firstFailure == null ? new IllegalStateException("No provider candidates available") : firstFailure;
    }

    private Outcome failNoProvider(TaskRequest task, List<Object> selectedTools) {
        router.route(task, selectedTools);
        throw new IllegalStateException("No provider candidates available");
    }

    private List<ProviderConfig> orderedFallbacks(List<RoutingCandidate> ranked) {
        ProviderConfig primary = ranked.getFirst().provider();
        List<ProviderConfig> ordered = new ArrayList<>();
        ordered.add(primary);
        List<String> configured = config.reliability().fallbackOrder().getOrDefault(primary.id(), List.of());
        for (String id : configured) ranked.stream().map(RoutingCandidate::provider)
                .filter(provider -> provider.id().equals(id) && !ordered.contains(provider)).findFirst().ifPresent(ordered::add);
        ranked.stream().map(RoutingCandidate::provider).filter(provider -> !ordered.contains(provider)).forEach(ordered::add);
        return ordered;
    }

    /** Streams tokens as they arrive via {@code onToken}; blocks until the response completes, then
     * returns the same {@link Outcome} shape as {@link #run(TaskRequest)}. Tools are not available
     * on the streaming path. */
    public Outcome runStreaming(TaskRequest task, Consumer<String> onToken) {
        Attributes attributes = Attributes.builder().put("gen_ai.operation.name", "chat")
                .put("castcli.streaming", true).put("castcli.workload", task.workload().name()).build();
        telemetry.request(attributes);
        try (CastTelemetry.SpanScope span = telemetry.span("castcli.request").attributes(attributes)) {
            telemetry.annotatePrompt(span, task.prompt());
            try {
                Outcome outcome = runStreamingCore(task, onToken);
                span.attribute("gen_ai.provider.name", outcome.provider().id())
                        .attribute("gen_ai.request.model", outcome.provider().modelName())
                        .attribute("gen_ai.usage.input_tokens", outcome.inputTokens())
                        .attribute("gen_ai.usage.output_tokens", outcome.outputTokens());
                telemetry.modelUsage(outcome.inputTokens(), outcome.outputTokens(), outcome.estimatedCostUsd(),
                        outcome.durationMs(), providerAttributes(outcome.provider()));
                return outcome.withTraceId(span.traceId());
            } catch (RuntimeException failure) {
                span.error(failure); telemetry.failure(attributes); throw failure;
            }
        }
    }

    private Outcome runStreamingCore(TaskRequest task, Consumer<String> onToken) {
        long startTime = System.currentTimeMillis();
        ProviderConfig provider = router.route(task, List.of());
        TaskRequest executionTask = memoryContextProvider == null ? task
                : new TaskRequest(memoryContextProvider.augment(task.prompt()), task.workload(), task.requestedTier(), task.strict());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.reliability().requestDeadlineSeconds());
        return reliabilityExecutor.execute(provider,
                () -> streamWithProvider(executionTask, onToken, startTime, provider), false, deadline);
    }

    private Outcome streamWithProvider(TaskRequest task, Consumer<String> onToken, long startTime, ProviderConfig provider) {
        try (var span = telemetry.span("gen_ai.chat")
                .attribute("gen_ai.operation.name", "chat")
                .attribute("gen_ai.provider.name", provider.id())
                .attribute("gen_ai.request.model", provider.modelName())) {
        telemetry.annotatePrompt(span, task.prompt());
        StreamingChatModel model = modelFactory.createStreaming(provider);

        StringBuilder answer = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        TokenUsage[] usageHolder = new TokenUsage[1];
        RuntimeException[] errorHolder = new RuntimeException[1];

        ChatRequest request = ChatRequest.builder().messages(UserMessage.from(task.prompt())).build();
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                answer.append(partialResponse);
                onToken.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                usageHolder[0] = completeResponse.metadata() == null ? null : completeResponse.metadata().tokenUsage();
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorHolder[0] = new RuntimeException("Streaming chat failed", error);
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for streamed response", e);
        }
        if (errorHolder[0] != null) {
            throw errorHolder[0];
        }

        long duration = System.currentTimeMillis() - startTime;
        long inputTokens = tokensOrZero(usageHolder[0] == null ? null : usageHolder[0].inputTokenCount());
        long outputTokens = tokensOrZero(usageHolder[0] == null ? null : usageHolder[0].outputTokenCount());
        double cost = provider.estimatedCostUsd(inputTokens, outputTokens);
        span.attribute("gen_ai.usage.input_tokens", inputTokens)
                .attribute("gen_ai.usage.output_tokens", outputTokens);
        // Tokens are already delivered live via onToken as they stream; only the aggregated answer
        // recorded in the Outcome (e.g. for memory persistence) can be redacted after the fact.
        return new Outcome(provider, GuardrailFilter.filter(answer.toString()), List.of(), List.of(), duration, false, inputTokens, outputTokens, cost);
        }
    }

    private Outcome executeWithProvider(
            TaskRequest task,
            ProviderConfig provider,
            List<Object> selectedTools,
            List<String> selectedToolNames,
            long startTime) {
        try (var span = telemetry.span("gen_ai.chat")
                .attribute("gen_ai.operation.name", "chat")
                .attribute("gen_ai.provider.name", provider.id())
                .attribute("gen_ai.request.model", provider.modelName())) {
        telemetry.annotatePrompt(span, task.prompt());
        ChatModel model = modelFactory.create(provider);

        if (!provider.toolsEnabled() || (selectedTools.isEmpty() && mcpToolProvider == null)) {
            ChatResponse response = model.chat(ChatRequest.builder().messages(UserMessage.from(task.prompt())).build());
            long duration = System.currentTimeMillis() - startTime;
            long inputTokens = tokensOrZero(response.metadata() == null || response.metadata().tokenUsage() == null
                    ? null : response.metadata().tokenUsage().inputTokenCount());
            long outputTokens = tokensOrZero(response.metadata() == null || response.metadata().tokenUsage() == null
                    ? null : response.metadata().tokenUsage().outputTokenCount());
            double cost = provider.estimatedCostUsd(inputTokens, outputTokens);
            span.attribute("gen_ai.usage.input_tokens", inputTokens)
                    .attribute("gen_ai.usage.output_tokens", outputTokens);
            return new Outcome(provider, GuardrailFilter.filter(response.aiMessage().text()),
                    selectedToolNames, List.of(), duration, false, inputTokens, outputTokens, cost);
        }

        AiServices<Assistant> builder = AiServices.builder(Assistant.class)
                .chatModel(model)
                .tools(selectedTools);
        if (mcpToolProvider != null) {
            builder = builder.toolProvider(mcpToolProvider);
        }
        Assistant assistant = builder.build();

        Result<String> result = assistant.chat(task.prompt());
        List<String> toolsUsed = result.toolExecutions().stream()
                .map(execution -> execution.request().name())
                .toList();
        long duration = System.currentTimeMillis() - startTime;
        TokenUsage usage = result.tokenUsage();
        long inputTokens = tokensOrZero(usage == null ? null : usage.inputTokenCount());
        long outputTokens = tokensOrZero(usage == null ? null : usage.outputTokenCount());
        double cost = provider.estimatedCostUsd(inputTokens, outputTokens);
        span.attribute("gen_ai.usage.input_tokens", inputTokens)
                .attribute("gen_ai.usage.output_tokens", outputTokens);
        return new Outcome(provider, GuardrailFilter.filter(result.content()), selectedToolNames, toolsUsed, duration, false, inputTokens, outputTokens, cost);
        }
    }

    private static long tokensOrZero(Integer count) {
        return count == null ? 0L : count.longValue();
    }

    private static Attributes providerAttributes(ProviderConfig provider) {
        return Attributes.builder().put("gen_ai.provider.name", provider.id())
                .put("gen_ai.request.model", provider.modelName())
                .put("castcli.provider.tier", provider.tier().name()).build();
    }
}
