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
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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

    /**
     * Result of {@link #runWithClientTools}: either a text answer ({@code toolCalls} empty) or a
     * set of tool calls the model wants the <em>caller</em> to execute ({@code answer} null). Unlike
     * {@link Outcome}, tool calls here are never executed by CastCLI -- see {@link #runWithClientTools}.
     */
    public record ClientToolOutcome(
            ProviderConfig provider,
            String answer,
            List<ToolExecutionRequest> toolCalls,
            String finishReason,
            long durationMs,
            long inputTokens,
            long outputTokens,
            double estimatedCostUsd,
            String traceId) {
        public ClientToolOutcome(ProviderConfig provider, String answer, List<ToolExecutionRequest> toolCalls,
                                  String finishReason, long durationMs, long inputTokens, long outputTokens,
                                  double estimatedCostUsd) {
            this(provider, answer, toolCalls, finishReason, durationMs, inputTokens, outputTokens, estimatedCostUsd, null);
        }

        ClientToolOutcome withTraceId(String id) {
            return new ClientToolOutcome(provider, answer, toolCalls, finishReason, durationMs,
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
        return run(task, List.of());
    }

    /**
     * Runs a chat turn, prepending {@code history} (prior turns, e.g. a system prompt and earlier
     * user/assistant messages) before the current turn built from {@code task.prompt()}. Passing
     * real {@link ChatMessage}s instead of flattening history into the prompt string preserves role
     * boundaries the model can actually use (a {@code SystemMessage} behaves differently from a user
     * message containing the literal text "system: ..."). {@code task.prompt()} remains the single
     * source of truth for fastPath/tool-selection heuristics, routing signals, memory augmentation,
     * and telemetry -- it is always the current turn, never the whole conversation.
     */
    public Outcome run(TaskRequest task, List<ChatMessage> history) {
        Attributes requestAttributes = Attributes.builder()
                .put("gen_ai.operation.name", "chat")
                .put("castcli.workload", task.workload().name())
                .put("castcli.routing.strict", task.strict())
                .build();
        telemetry.request(requestAttributes);
        try (CastTelemetry.SpanScope span = telemetry.span("castcli.request").attributes(requestAttributes)) {
            telemetry.annotatePrompt(span, task.prompt());
            try {
                Outcome outcome = runCore(task, history);
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

    /**
     * Runs a chat turn with client-supplied (OpenAI-style) tool specifications, without executing
     * any of them: the model may either answer with text or request tool calls, and either way
     * CastCLI hands the result straight back to the caller. This is a deliberately different control
     * flow from {@link #run(TaskRequest)}'s server-side {@code AiServices}-executed tools -- those are
     * CastCLI's own {@code ApprovalGate}-gated tools; these belong to and are executed by the caller
     * (e.g. the OpenAI-compatible gateway's client). The two are not combined in one call. Routing
     * reuses {@code task}'s workload/tier/strictness, but {@code messages} (not {@code task.prompt()})
     * is what is actually sent to the model -- {@code task.prompt()} only drives routing signals and
     * telemetry annotation. There is no fallback across providers mid-tool-call: a failure with the
     * routed provider fails the request rather than retrying with a different one, matching the
     * existing single-candidate rule for server-side tool calls in {@link #runCore}.
     */
    public ClientToolOutcome runWithClientTools(TaskRequest task, List<ChatMessage> messages,
                                                  List<ToolSpecification> toolSpecifications, ToolChoice toolChoice) {
        Attributes attributes = Attributes.builder()
                .put("gen_ai.operation.name", "chat")
                .put("castcli.client_tools", true)
                .put("castcli.workload", task.workload().name())
                .build();
        telemetry.request(attributes);
        try (CastTelemetry.SpanScope span = telemetry.span("castcli.request").attributes(attributes)) {
            telemetry.annotatePrompt(span, task.prompt());
            try {
                ClientToolOutcome outcome = runClientToolsCore(task, messages, toolSpecifications, toolChoice);
                span.attribute("gen_ai.provider.name", outcome.provider().id())
                        .attribute("gen_ai.request.model", outcome.provider().modelName())
                        .attribute("gen_ai.usage.input_tokens", outcome.inputTokens())
                        .attribute("gen_ai.usage.output_tokens", outcome.outputTokens())
                        .attribute("castcli.estimated.cost.usd", outcome.estimatedCostUsd());
                telemetry.modelUsage(outcome.inputTokens(), outcome.outputTokens(), outcome.estimatedCostUsd(),
                        outcome.durationMs(), providerAttributes(outcome.provider()));
                return outcome.withTraceId(span.traceId());
            } catch (RuntimeException failure) {
                span.error(failure);
                telemetry.failure(attributes);
                throw failure;
            }
        }
    }

    private ClientToolOutcome runClientToolsCore(TaskRequest task, List<ChatMessage> messages,
                                                   List<ToolSpecification> toolSpecifications, ToolChoice toolChoice) {
        long startTime = System.currentTimeMillis();
        List<Object> toolsForRouting = List.copyOf(toolSpecifications);
        List<RoutingCandidate> ranked = router.rank(task, toolsForRouting);
        if (ranked.isEmpty()) {
            router.route(task, toolsForRouting);
            throw new IllegalStateException("No provider candidates available");
        }
        // No cross-provider fallback mid-tool-call, same rule runCore applies whenever tools are
        // present: a partially-executed tool round-trip against provider A cannot be silently
        // retried against provider B.
        ProviderConfig provider = orderedFallbacks(ranked).getFirst();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.reliability().requestDeadlineSeconds());
        return reliabilityExecutor.execute(provider,
                () -> executeClientTools(messages, toolSpecifications, toolChoice, provider, startTime), false, deadline);
    }

    private ClientToolOutcome executeClientTools(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications,
                                                   ToolChoice toolChoice, ProviderConfig provider, long startTime) {
        try (var span = telemetry.span("gen_ai.chat")
                .attribute("gen_ai.operation.name", "chat")
                .attribute("gen_ai.provider.name", provider.id())
                .attribute("gen_ai.request.model", provider.modelName())) {
        ChatModel model = modelFactory.create(provider);
        ChatRequest.Builder requestBuilder = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecifications);
        if (toolChoice != null) {
            requestBuilder.toolChoice(toolChoice);
        }
        ChatResponse response = model.chat(requestBuilder.build());
        AiMessage aiMessage = response.aiMessage();

        long duration = System.currentTimeMillis() - startTime;
        TokenUsage usage = response.metadata() == null ? null : response.metadata().tokenUsage();
        long inputTokens = tokensOrZero(usage == null ? null : usage.inputTokenCount());
        long outputTokens = tokensOrZero(usage == null ? null : usage.outputTokenCount());
        double cost = provider.estimatedCostUsd(inputTokens, outputTokens);
        FinishReason finishReason = response.metadata() == null ? null : response.metadata().finishReason();
        String finishReasonText = mapFinishReason(finishReason, aiMessage.hasToolExecutionRequests());
        span.attribute("gen_ai.usage.input_tokens", inputTokens)
                .attribute("gen_ai.usage.output_tokens", outputTokens);

        if (aiMessage.hasToolExecutionRequests()) {
            // Some OpenAI-compatible providers (notably Ollama) omit the tool-call id entirely; a
            // null id there would make it impossible for the caller to match a later "tool" role
            // result back to this call, so synthesize one rather than passing null through.
            List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests().stream()
                    .map(request -> request.id() == null || request.id().isBlank()
                            ? request.toBuilder().id("call_" + UUID.randomUUID()).build()
                            : request)
                    .toList();
            return new ClientToolOutcome(provider, null, toolCalls, finishReasonText, duration, inputTokens, outputTokens, cost);
        }
        return new ClientToolOutcome(provider, GuardrailFilter.filter(aiMessage.text()), List.of(), finishReasonText,
                duration, inputTokens, outputTokens, cost);
        }
    }

    private static String mapFinishReason(FinishReason reason, boolean hasToolExecutionRequests) {
        if (reason == null) {
            return hasToolExecutionRequests ? "tool_calls" : "stop";
        }
        return switch (reason) {
            case TOOL_EXECUTION -> "tool_calls";
            case LENGTH -> "length";
            case CONTENT_FILTER -> "content_filter";
            case STOP, OTHER -> hasToolExecutionRequests ? "tool_calls" : "stop";
        };
    }

    private Outcome runCore(TaskRequest task, List<ChatMessage> history) {
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
                        () -> executeWithProvider(executionTask, provider, selectedTools, selectedToolNames, startTime, history),
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
     * on the streaming path. Never returns early on client cancellation -- see the 3-arg overload. */
    public Outcome runStreaming(TaskRequest task, Consumer<String> onToken) {
        return runStreaming(task, onToken, () -> false);
    }

    /** As {@link #runStreaming(TaskRequest, Consumer, BooleanSupplier)}, but prepends {@code history}
     * before the current turn, the same way {@link #run(TaskRequest, List)} does for the non-streaming
     * path. */
    public Outcome runStreaming(TaskRequest task, Consumer<String> onToken, BooleanSupplier cancelled,
                                  List<ChatMessage> history) {
        Attributes attributes = Attributes.builder().put("gen_ai.operation.name", "chat")
                .put("castcli.streaming", true).put("castcli.workload", task.workload().name()).build();
        telemetry.request(attributes);
        try (CastTelemetry.SpanScope span = telemetry.span("castcli.request").attributes(attributes)) {
            telemetry.annotatePrompt(span, task.prompt());
            try {
                Outcome outcome = runStreamingCore(task, onToken, cancelled, history);
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

    /** Streams tokens as they arrive via {@code onToken}; blocks until the response completes or
     * {@code cancelled} reports {@code true}, then returns the same {@link Outcome} shape as
     * {@link #run(TaskRequest)}. {@code cancelled} is polled (not interrupt-driven) so a caller such
     * as the HTTP gateway can signal "the client disconnected" without needing a cooperative
     * cancellation hook into the underlying provider client -- none of the current model adapters
     * expose one. On cancellation the in-flight provider call is <em>not</em> aborted; it keeps
     * running in the background until it completes or the reliability-layer deadline reclaims it.
     * This bounds how long the caller waits, not how long the provider keeps generating. Tools are
     * not available on the streaming path. */
    public Outcome runStreaming(TaskRequest task, Consumer<String> onToken, BooleanSupplier cancelled) {
        return runStreaming(task, onToken, cancelled, List.of());
    }

    private Outcome runStreamingCore(TaskRequest task, Consumer<String> onToken, BooleanSupplier cancelled,
                                       List<ChatMessage> history) {
        long startTime = System.currentTimeMillis();
        ProviderConfig provider = router.route(task, List.of());
        TaskRequest executionTask = memoryContextProvider == null ? task
                : new TaskRequest(memoryContextProvider.augment(task.prompt()), task.workload(), task.requestedTier(), task.strict());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.reliability().requestDeadlineSeconds());
        return reliabilityExecutor.execute(provider,
                () -> streamWithProvider(executionTask, onToken, startTime, provider, cancelled, history), false, deadline);
    }

    private static final long CANCELLATION_POLL_MILLIS = 200;

    private Outcome streamWithProvider(TaskRequest task, Consumer<String> onToken, long startTime,
                                        ProviderConfig provider, BooleanSupplier cancelled, List<ChatMessage> history) {
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

        ChatRequest request = ChatRequest.builder().messages(withCurrentTurn(history, task.prompt())).build();
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

        boolean cancelledEarly = false;
        try {
            while (!latch.await(CANCELLATION_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    cancelledEarly = true;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for streamed response", e);
        }
        if (cancelledEarly) {
            span.attribute("castcli.streaming.cancelled", true);
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
        // Client cancellation is treated as a normal return (not a thrown failure) specifically so
        // ReliabilityExecutor.execute records it as a success rather than dinging provider health --
        // a user closing their editor mid-stream says nothing about the provider's reliability.
        return new Outcome(provider, GuardrailFilter.filter(answer.toString()), List.of(), List.of(), duration, false, inputTokens, outputTokens, cost);
        }
    }

    private Outcome executeWithProvider(
            TaskRequest task,
            ProviderConfig provider,
            List<Object> selectedTools,
            List<String> selectedToolNames,
            long startTime,
            List<ChatMessage> history) {
        try (var span = telemetry.span("gen_ai.chat")
                .attribute("gen_ai.operation.name", "chat")
                .attribute("gen_ai.provider.name", provider.id())
                .attribute("gen_ai.request.model", provider.modelName())) {
        telemetry.annotatePrompt(span, task.prompt());

        if (provider.tier() == ModelTier.FRONTIER_CLOUD) {
            dev.justnels.castcli.security.ContextFirewall firewall = new dev.justnels.castcli.security.ContextFirewall();
            dev.justnels.castcli.security.ContextFirewall.FirewallDecision decision = firewall.inspect(task.prompt(), List.of());
            if (!decision.allowed()) {
                throw new SecurityException("Context Firewall blocked cloud dispatch to provider '"
                        + provider.id() + "': " + decision.denialReason());
            }
            try {
                dev.justnels.castcli.security.EgressManifest manifest = new dev.justnels.castcli.security.EgressManifest(
                        span.traceId(),
                        null,
                        provider.id(),
                        provider.modelName(),
                        decision.classification(),
                        0,
                        task.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        task.prompt().length() / 4,
                        telemetry.promptHash(task.prompt()),
                        List.of(),
                        true);
                Path egressDir = Path.of(config.tools().workspaceRoot()).resolve(".cast/egress");
                manifest.saveTo(egressDir);
            } catch (Exception ignored) {
            }
        }

        ChatModel model = modelFactory.create(provider);

        if (!provider.toolsEnabled() || (selectedTools.isEmpty() && mcpToolProvider == null)) {
            List<ChatMessage> messages = withCurrentTurn(history, task.prompt());
            ChatResponse response = model.chat(ChatRequest.builder().messages(messages).build());
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

        // AiServices.Assistant only takes a single prompt string, not a message list, so history
        // (when present) is flattened and prepended rather than dropped -- losing it here would be a
        // silent regression on any request where CastCLI's own tool selector fires, not just a
        // documented gap. This branch is CastCLI's own ApprovalGate-gated tool execution, a
        // deliberately different path from the "no tools" branch above where a real message list is
        // used directly.
        String prompt = history.isEmpty() ? task.prompt() : flattenHistoryForPrompt(history) + "\n\n" + task.prompt();
        Result<String> result = assistant.chat(prompt);
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

    private static List<ChatMessage> withCurrentTurn(List<ChatMessage> history, String currentPrompt) {
        if (history.isEmpty()) {
            return List.of(UserMessage.from(currentPrompt));
        }
        List<ChatMessage> messages = new ArrayList<>(history);
        messages.add(UserMessage.from(currentPrompt));
        return messages;
    }

    /** Renders prior turns as a single role-tagged block, used only as a fallback so CastCLI's own
     * (AiServices-executed) tools never silently lose conversation history -- see the comment where
     * this is called in {@link #executeWithProvider}. */
    private static String flattenHistoryForPrompt(List<ChatMessage> history) {
        StringBuilder rendered = new StringBuilder();
        for (ChatMessage message : history) {
            String role;
            String text;
            if (message instanceof SystemMessage system) {
                role = "system";
                text = system.text();
            } else if (message instanceof UserMessage user) {
                role = "user";
                text = user.hasSingleText() ? user.singleText() : user.toString();
            } else if (message instanceof AiMessage ai) {
                role = "assistant";
                text = ai.text() == null ? "" : ai.text();
            } else if (message instanceof ToolExecutionResultMessage toolResult) {
                role = "tool";
                text = toolResult.text();
            } else {
                role = "message";
                text = message.toString();
            }
            if (!rendered.isEmpty()) {
                rendered.append("\n\n");
            }
            rendered.append(role).append(": ").append(text);
        }
        return rendered.toString();
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
