package dev.justnels.castcli.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.index.WorkspaceEmbeddingIndex;
import dev.justnels.castcli.model.ChatModelFactory;
import dev.justnels.castcli.model.EmbeddingModelFactory;
import dev.justnels.castcli.observability.CastTelemetry;
import dev.justnels.castcli.memory.CachingMemoryStore;
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
import dev.justnels.castcli.tools.ProcessExecTool;
import dev.justnels.castcli.tools.SemanticSearchTools;
import dev.justnels.castcli.tools.SystemTools;
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

import java.io.IOException;
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
    private static final ObjectMapper TEXT_TOOL_JSON = new ObjectMapper();
    private static final int MAX_TEXT_TOOL_CALLS = 8;
    private static final int MAX_TEXT_TOOL_RESULT_CHARS = 4_000;
    private static final int MAX_INTERNAL_OUTPUT_TOKENS = 768;
    private static final int MAX_WORKER_OUTPUT_TOKENS = 2_048;
    private static final String TEXT_TOOL_PROTOCOL = """

            LOCAL TOOL FALLBACK: If you cannot emit native tool calls, return only JSON. For one call use
            {"name":"toolName","arguments":{...}}. When creating or updating multiple independent files,
            batch them in one response as {"toolCalls":[{"name":"writeWorkspaceFile","arguments":{...}}, ...]}.
            Never claim a tool succeeded until CastCLI returns its actual result.
            """;
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
            this.memoryStore = new CachingMemoryStore(new SqliteMemoryStore(databasePath));
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

        List<Object> selectedTools = new ArrayList<>(task.toolsDisabled()
                ? List.of() : toolSelector.selectTools(task, config.tools(), approvalGate));
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
                : new TaskRequest(memoryContextProvider.augment(task.prompt()), task.workload(), task.requestedTier(),
                        task.requestedProviderId(), task.strict(), task.toolsDisabled());
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
                : new TaskRequest(memoryContextProvider.augment(task.prompt()), task.workload(), task.requestedTier(),
                        task.requestedProviderId(), task.strict(), task.toolsDisabled());
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

        int defaultOutputLimit = task.toolsDisabled() ? MAX_INTERNAL_OUTPUT_TOKENS : MAX_WORKER_OUTPUT_TOKENS;
        int outputLimit = provider.maxOutputTokens() == null
                ? defaultOutputLimit : Math.min(provider.maxOutputTokens(), defaultOutputLimit);
        ChatModel model = modelFactory.create(provider.withMaxOutputTokens(outputLimit));

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
        String toolPrompt = prompt + TEXT_TOOL_PROTOCOL;
        Result<String> result = assistant.chat(toolPrompt);
        String currentContent = result.content();
        TokenUsage currentUsage = result.tokenUsage();
        boolean hasNativeToolExecutions = !result.toolExecutions().isEmpty();
        List<String> toolsUsed = new ArrayList<>();
        toolsUsed.addAll(result.toolExecutions().stream()
                .map(execution -> execution.request().name())
                .toList());
        long inputTokens = 0;
        long outputTokens = 0;
        List<ChatMessage> textToolHistory = new ArrayList<>();
        textToolHistory.add(UserMessage.from(toolPrompt));
        int textToolCalls = 0;
        int nativeContinuationRounds = 0;
        while (true) {
            inputTokens += tokensOrZero(currentUsage == null ? null : currentUsage.inputTokenCount());
            outputTokens += tokensOrZero(currentUsage == null ? null : currentUsage.outputTokenCount());

            List<TextToolCall> textCalls = parseTextToolCalls(currentContent);
            if (hasNativeToolExecutions) {
                if (currentContent != null && !currentContent.isBlank()) break;
                if (nativeContinuationRounds++ >= MAX_TEXT_TOOL_CALLS) {
                    throw new IllegalStateException("Local model executed tools but produced no final answer after "
                            + MAX_TEXT_TOOL_CALLS + " continuation rounds");
                }
                Result<String> continued = assistant.chat(toolPrompt
                        + "\n\nCastCLI executed your prior native tool calls: " + toolsUsed + ". "
                        + "Inspect the current workspace state and continue all missing implementation, test, and "
                        + "documentation work. Do not repeat completed writes. Return a concise factual summary only "
                        + "after the entire original task is complete.");
                currentContent = continued.content();
                currentUsage = continued.tokenUsage();
                List<String> continuedTools = continued.toolExecutions().stream()
                        .map(execution -> execution.request().name())
                        .toList();
                toolsUsed.addAll(continuedTools);
                hasNativeToolExecutions = !continuedTools.isEmpty();
                continue;
            }
            if (textCalls.isEmpty()) {
                break;
            }
            if (textToolCalls + textCalls.size() > MAX_TEXT_TOOL_CALLS) {
                throw new IllegalStateException("Local model exceeded the text-form tool-call limit of "
                        + MAX_TEXT_TOOL_CALLS);
            }
            textToolCalls += textCalls.size();
            StringBuilder toolResults = new StringBuilder("CastCLI executed the requested tools:\n");
            for (TextToolCall call : textCalls) {
                String toolResult = executeTextToolCall(call, selectedTools);
                toolsUsed.add(call.name());
                toolResults.append("\nRESULT for ").append(call.name()).append(":\n")
                        .append(tail(toolResult, MAX_TEXT_TOOL_RESULT_CHARS)).append('\n');
            }
            toolResults.append("\nContinue the original task. Batch independent file writes when possible. "
                    + "Do not repeat an inspection whose result is already above. When the task is actually complete, "
                    + "return a concise factual summary.");
            textToolHistory.add(AiMessage.from(currentContent));
            textToolHistory.add(UserMessage.from(toolResults.toString()));
            ChatResponse continuation = model.chat(ChatRequest.builder().messages(textToolHistory).build());
            currentContent = continuation.aiMessage().text();
            currentUsage = continuation.metadata() == null ? null : continuation.metadata().tokenUsage();
            hasNativeToolExecutions = false;
        }
        long duration = System.currentTimeMillis() - startTime;
        double cost = provider.estimatedCostUsd(inputTokens, outputTokens);
        span.attribute("gen_ai.usage.input_tokens", inputTokens)
                .attribute("gen_ai.usage.output_tokens", outputTokens);
        return new Outcome(provider, GuardrailFilter.filter(currentContent), selectedToolNames, toolsUsed, duration, false, inputTokens, outputTokens, cost);
        }
    }

    private record TextToolCall(String name, JsonNode arguments) {}

    private static List<TextToolCall> parseTextToolCalls(String content) {
        if (content == null || content.isBlank()) return List.of();
        String candidate = content.strip();
        if (candidate.startsWith("```json") && candidate.endsWith("```")) {
            candidate = candidate.substring(7, candidate.length() - 3).strip();
        } else if (candidate.startsWith("```") && candidate.endsWith("```")) {
            candidate = candidate.substring(3, candidate.length() - 3).strip();
        }
        try {
            JsonNode root = TEXT_TOOL_JSON.readTree(candidate);
            if (root.isObject() && root.size() == 1 && root.path("toolCalls").isArray()) {
                List<TextToolCall> calls = new ArrayList<>();
                for (JsonNode item : root.path("toolCalls")) {
                    TextToolCall call = parseTextToolCallObject(item);
                    if (call == null) return List.of();
                    calls.add(call);
                }
                return calls.isEmpty() ? List.of() : List.copyOf(calls);
            }
            TextToolCall call = parseTextToolCallObject(root);
            return call == null ? List.of() : List.of(call);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static TextToolCall parseTextToolCallObject(JsonNode root) {
        if (!root.isObject() || root.size() != 2 || !root.has("name") || !root.has("arguments")
                || !root.path("name").isTextual() || !root.path("arguments").isObject()) {
            return null;
        }
        return new TextToolCall(root.path("name").textValue(), root.path("arguments"));
    }

    private static String executeTextToolCall(TextToolCall call, List<Object> selectedTools) {
        try {
            for (Object tool : selectedTools) {
                if (tool instanceof WorkspaceTools workspace) {
                    String result = executeWorkspaceTextTool(call, workspace);
                    if (result != null) return result;
                } else if (tool instanceof ProcessExecTool process && call.name().equals("runCommand")) {
                    return process.runCommand(requiredText(call.arguments(), "commandKey"));
                } else if (tool instanceof SystemTools system && call.name().equals("currentTime")) {
                    return system.currentTime(requiredText(call.arguments(), "zoneId"));
                } else if (tool instanceof SemanticSearchTools semantic
                        && call.name().equals("semanticSearchWorkspace")) {
                    return semantic.semanticSearchWorkspace(requiredText(call.arguments(), "query"),
                            optionalInt(call.arguments(), "maxResults", 10)).toString();
                }
            }
            throw new IllegalArgumentException("Text-form tool is not selected or supported: " + call.name());
        } catch (Exception failure) {
            return "Tool execution failed: " + failure.getMessage();
        }
    }

    private static String executeWorkspaceTextTool(TextToolCall call, WorkspaceTools workspace) throws IOException {
        return switch (call.name()) {
            case "readWorkspaceFile" -> workspace.readWorkspaceFile(requiredText(call.arguments(), "path"));
            case "listWorkspaceFiles" -> workspace.listWorkspaceFiles(
                    requiredText(call.arguments(), "glob"), optionalInt(call.arguments(), "maxResults", 100)).toString();
            case "searchWorkspace" -> workspace.searchWorkspace(
                    requiredText(call.arguments(), "query"), optionalInt(call.arguments(), "maxResults", 100)).toString();
            case "writeWorkspaceFile" -> workspace.writeWorkspaceFile(
                    requiredText(call.arguments(), "path"), requiredText(call.arguments(), "content"));
            default -> null;
        };
    }

    private static String requiredText(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Missing text argument: " + name);
        }
        return value.textValue();
    }

    private static int optionalInt(JsonNode arguments, String name, int defaultValue) {
        JsonNode value = arguments.get(name);
        return value == null ? defaultValue : value.asInt(defaultValue);
    }

    private static String tail(String text, int maxChars) {
        return text.length() <= maxChars ? text : "...[earlier tool transcript omitted]\n"
                + text.substring(text.length() - maxChars);
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
