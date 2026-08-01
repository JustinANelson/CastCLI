package dev.justnels.castcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.justnels.castcli.cache.DeterministicResultCache;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.ReliabilityConfig;
import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.doctor.BuildInfo;
import dev.justnels.castcli.index.IndexerIgnoreConfig;
import dev.justnels.castcli.index.WorkspaceEmbeddingIndex;
import dev.justnels.castcli.model.EmbeddingModelFactory;
import dev.justnels.castcli.memory.SessionMemorySummarizer;
import dev.justnels.castcli.memory.SqliteMemoryStore;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import dev.justnels.castcli.observability.CastTelemetry;
import io.opentelemetry.api.common.Attributes;
import dev.justnels.castcli.tools.MemoryTools;
import dev.justnels.castcli.tools.SemanticSearchTools;
import dev.justnels.castcli.tools.WorkspaceTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * A minimal MCP server speaking newline-delimited JSON-RPC 2.0 over
 * stdio, implemented directly against the spec rather than a third-party server SDK. It exposes this
 * harness's cheap local/small tiers as MCP tools so a frontier agent such as Claude Code can delegate
 * routine, low-stakes subtasks down to them instead of spending frontier tokens on everything.
 *
 * <p>Deliberately excludes {@link ModelTier#FRONTIER_CLOUD} providers from {@code ask_local}: this
 * server exists to offload work to the cheap tier, not to proxy frontier calls.
 */
public final class McpStdioServer {
    private static final Logger log = LoggerFactory.getLogger(McpStdioServer.class);
    private static final String PROTOCOL_VERSION = "2025-11-25";
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
            "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final long FAILED_REQUEST_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final BufferedReader in;
    private final PrintStream out;
    private final CastTelemetry telemetry;
    private final McpUsageStore usageStore;
    private final boolean auditEnabled;
    private final boolean includeResponseMetadata;
    private final DeterministicResultCache resultCache = new DeterministicResultCache();
    private final ConcurrentMap<String, Long> recentFailedDelegations = new ConcurrentHashMap<>();
    private final int maxToolResultChars;
    private boolean initializeResponded;
    private boolean initialized;

    public McpStdioServer(HarnessConfig config, InputStream stdin, PrintStream stdout) {
        this(config, stdin, stdout, null);
    }

    McpStdioServer(HarnessConfig config, InputStream stdin, PrintStream stdout,
                   HarnessOrchestrator askLocalOrchestrator) {
        this.in = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        this.out = stdout;
        Path workspace = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
        try {
            IndexerIgnoreConfig.ensureCreated(workspace);
        } catch (IOException e) {
            log.warn("Failed to create workspace indexer ignore configuration: {}", e.getMessage());
        }
        this.telemetry = CastTelemetry.initialize(config.observability(), workspace);
        this.auditEnabled = config.mcpAudit().enabled();
        this.includeResponseMetadata = config.mcpAudit().includeResponseMetadata();
        this.maxToolResultChars = Math.max(1_000, Math.min(4_000, config.routing().maxContextChars() / 3));
        this.usageStore = new McpUsageStore(McpUsageStore.resolvePath(config.mcpAudit(), workspace));
        registerTools(config, askLocalOrchestrator);
    }

    public static McpStdioServer forStdio(HarnessConfig config) {
        return new McpStdioServer(config, System.in, System.out);
    }

    public void serve() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            handleLine(line);
        }
    }

    private void handleLine(String line) {
        JsonNode request;
        try {
            request = mapper.readTree(line);
        } catch (Exception e) {
            log.warn("Malformed JSON-RPC line: {}", e.getMessage());
            sendError(mapper.nullNode(), -32700, "Parse error");
            return;
        }

        JsonNode idNode = request.get("id");
        String method = request.path("method").asText("");

        if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").asText()) || method.isBlank()) {
            if (idNode != null) {
                sendError(idNode, -32600, "Invalid JSON-RPC request");
            }
            return;
        }
        if (method.startsWith("notifications/")) {
            if ("notifications/initialized".equals(method) && initializeResponded) {
                initialized = true;
            }
            return; // no response expected
        }

        try (var span = telemetry.span("mcp.request")
                .attribute("rpc.system", "jsonrpc")
                .attribute("rpc.method", method)
                .attribute("rpc.jsonrpc.request_id", idNode == null ? "" : idNode.asText())) {
        try {
            ObjectNode result = dispatch(method, request.get("params"));
            if (idNode != null) {
                sendResult(idNode, result);
            }
        } catch (McpProtocolException e) {
            span.error(e);
            if (idNode != null) {
                sendError(idNode, e.code, e.getMessage());
            }
        } catch (Exception e) {
            span.error(e);
            if (idNode != null) {
                sendError(idNode, -32603, "Internal error: " + e.getMessage());
            }
        }
        }
    }

    private ObjectNode dispatch(String method, JsonNode params) throws Exception {
        return switch (method) {
            case "initialize" -> initializeResult(params);
            case "ping" -> mapper.createObjectNode();
            case "tools/list" -> {
                requireInitialized();
                yield toolsListResult();
            }
            case "tools/call" -> {
                requireInitialized();
                yield toolsCallResult(params);
            }
            default -> throw new McpProtocolException(-32601, "Method not found: " + method);
        };
    }

    private ObjectNode initializeResult(JsonNode params) {
        if (initializeResponded) {
            throw new McpProtocolException(-32600, "Server is already initialized");
        }
        String requested = params == null ? "" : params.path("protocolVersion").asText("");
        String negotiated = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;
        initializeResponded = true;
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "cast-cli");
        serverInfo.put("title", "CastCLI Local Delegation Server");
        serverInfo.put("version", BuildInfo.version());
        return result;
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new McpProtocolException(-32002,
                    "MCP session is not initialized; send initialize then notifications/initialized");
        }
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode toolsArray = result.putArray("tools");
        for (McpTool tool : tools.values()) {
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("name", tool.name());
            toolNode.put("description", tool.description());
            toolNode.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    private ObjectNode toolsCallResult(JsonNode params) throws Exception {
        if (params == null) {
            throw new McpProtocolException(-32602, "Missing params for tools/call");
        }
        String name = params.path("name").asText("");
        // MCP spec reserves _meta at the params level as a standard extension point.
        String callerModel = extractCallerModel(params);
        String invocationId = UUID.randomUUID().toString();
        long startedEpochMs = System.currentTimeMillis();
        long startedNanos = System.nanoTime();
        try (var span = telemetry.span("mcp.tool.call").attribute("mcp.tool.name", name)) {
        span.attribute("castcli.mcp.invocation_id", invocationId);
        telemetry.toolCall(Attributes.builder().put("mcp.tool.name", name).build());
        McpTool tool = tools.get(name);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        if (tool == null) {
            content.addObject().put("type", "text").put("text", "Unknown tool: " + name);
            result.put("isError", true);
            span.attribute("error.type", "unknown_tool");
            appendUsage(new McpUsageRecord(startedEpochMs, invocationId, span.traceId(), name, false,
                    elapsedMillis(startedNanos), null, null, null, 0, 0, 0, null, 0,
                    content.get(0).path("text").asText().length(), "unknown_tool", callerModel));
            return result;
        }
        JsonNode arguments = params.path("arguments");
        String argString = arguments == null ? "" : arguments.toString();
        boolean isReadOnly = isCacheableTool(name);
        String failureKey = isDelegationTool(name) ? telemetry.promptHash(name + "\n" + argString) : null;
        if (isReadOnly) {
            Optional<String> cached = resultCache.get(name, argString);
            if (cached.isPresent()) {
                String text = boundedText(cached.get(), maxToolResultChars);
                content.addObject().put("type", "text").put("text", text);
                if (includeResponseMetadata) {
                    ObjectNode metadata = result.putObject("_meta");
                    metadata.put("castcli/invocationId", invocationId);
                    metadata.put("castcli/cached", true);
                    var stats = resultCache.stats();
                    metadata.put("castcli/cacheEntries", stats.entries());
                    metadata.put("castcli/cacheBytes", stats.bytes());
                    metadata.put("castcli/cacheHits", stats.hits());
                    metadata.put("castcli/cacheEvictions", stats.evictions());
                    metadata.put("castcli/cacheKeySchema", stats.keySchemaVersion());
                }
                appendUsage(new McpUsageRecord(startedEpochMs, invocationId, span.traceId(), name, true,
                        elapsedMillis(startedNanos), null, null, null, 0, 0, 0, null,
                        argString.length(), text.length(), null, callerModel));
                return result;
            }
        }
        if (failureKey != null && suppressDuplicateFailure(failureKey)) {
            String errorText = "Identical failed delegation suppressed; continue with direct execution "
                    + "or reduce the request instead of retrying unchanged input.";
            content.addObject().put("type", "text").put("text", errorText);
            result.put("isError", true);
            addFailureMetadata(result, invocationId, "duplicate_suppressed", true);
            appendUsage(new McpUsageRecord(startedEpochMs, invocationId, span.traceId(), name, false,
                    elapsedMillis(startedNanos), null, null, null, 0, 0, 0, failureKey,
                    argString.length(), errorText.length(), "duplicate_suppressed", callerModel));
            return result;
        }
        try {
            McpTool.ExecutionResult execution = tool.handler().handle(arguments);
            String originalText = execution.text() == null ? "" : execution.text();
            String text = boundedText(originalText, maxToolResultChars);
            if (isReadOnly && text != null && !text.isBlank()) {
                resultCache.put(name, argString, text);
            }
            if (failureKey != null) recentFailedDelegations.remove(failureKey);
            McpTool.Delegation delegation = execution.delegation();
            if (delegation != null && includeResponseMetadata) {
                text = boundedText(text + delegationReceipt(invocationId, delegation), maxToolResultChars);
            }
            content.addObject().put("type", "text").put("text", text);
            if (includeResponseMetadata) {
                ObjectNode metadata = result.putObject("_meta");
                metadata.put("castcli/invocationId", invocationId);
                metadata.put("castcli/usageAuditPath", usageStore.path().toString());
                metadata.put("castcli/resultTruncated",
                        execution.truncated() || text.length() < originalText.length());
                if (isReadOnly) {
                    var stats = resultCache.stats();
                    metadata.put("castcli/cacheEntries", stats.entries());
                    metadata.put("castcli/cacheBytes", stats.bytes());
                    metadata.put("castcli/cacheMisses", stats.misses());
                    metadata.put("castcli/cacheEvictions", stats.evictions());
                    metadata.put("castcli/cacheKeySchema", stats.keySchemaVersion());
                }
                if (delegation != null) addDelegationMetadata(metadata, delegation);
            }
            appendUsage(toUsageRecord(startedEpochMs, startedNanos, invocationId, name, text, delegation, span.traceId(), callerModel));
        } catch (Exception e) {
            span.error(e);
            String errorType = classifyError(e);
            if (failureKey != null) recentFailedDelegations.put(failureKey, System.currentTimeMillis());
            String errorText = boundedText("Tool execution failed: " + e.getMessage()
                    + (failureKey == null ? "" : " Continue with direct execution; do not retry unchanged input."),
                    maxToolResultChars);
            content.addObject().put("type", "text").put("text", errorText);
            result.put("isError", true);
            addFailureMetadata(result, invocationId, errorType, false);
            appendUsage(new McpUsageRecord(startedEpochMs, invocationId, span.traceId(), name, false,
                    elapsedMillis(startedNanos), null, null, null, 0, 0, 0, null,
                    arguments.toString().length(), errorText.length(), errorType, callerModel));
        }
        return result;
        }
    }

    private void sendResult(JsonNode id, ObjectNode result) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id);
        envelope.set("result", result);
        write(envelope);
    }

    private void sendError(JsonNode id, int code, String message) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id);
        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        write(envelope);
    }

    private synchronized void write(ObjectNode envelope) {
        out.println(envelope.toString());
        out.flush();
    }

    private void registerTools(HarnessConfig config, HarnessOrchestrator askLocalOrchestrator) {
        HarnessConfig cheapOnlyConfig = restrictToCheapTiers(config);
        HarnessOrchestrator cheapOrchestrator = askLocalOrchestrator == null
                ? new HarnessOrchestrator(cheapOnlyConfig) : askLocalOrchestrator;
        WorkspaceTools workspaceTools = new WorkspaceTools(
                Path.of(config.tools().workspaceRoot()), config.tools().maxFileBytes());
        Path workspace = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
        Path configuredMemory = Path.of(config.memory().databasePath());
        SqliteMemoryStore sqliteMemoryStore = new SqliteMemoryStore(
                configuredMemory.isAbsolute() ? configuredMemory : workspace.resolve(configuredMemory));
        SessionMemorySummarizer sessionSummarizer = new SessionMemorySummarizer(
                sqliteMemoryStore, cheapOrchestrator, config.memory().defaultNamespace());
        MemoryTools memoryTools = new MemoryTools(sqliteMemoryStore, config.memory().defaultNamespace(), sessionSummarizer);

        registerAskLocal(cheapOrchestrator, config.routing().maxContextChars(), cheapOnlyConfig);
        registerListModels(cheapOnlyConfig);
        registerStructuredDelegationTools(new ReadOnlyDelegationTools(
                cheapOrchestrator, workspaceTools, telemetry, config.routing().maxContextChars(),
                TimeUnit.SECONDS.toMillis(config.reliability().mcpDelegationDeadlineSeconds()),
                preferredTier(cheapOnlyConfig, ModelTier.SMALL_LOCAL),
                preferredTier(cheapOnlyConfig, ModelTier.LARGE_LOCAL)));
        registerTool("read_workspace_file", "Reads a UTF-8 text file inside the configured workspace.",
                schema(Map.of("path", "string")), args ->
                        workspaceTools.readWorkspaceFile(args.path("path").asText()));
        registerTool("list_workspace_files", "Lists files inside the workspace using a glob such as **/*.java.",
                schema(Map.of("glob", "string", "maxResults", "integer")), args ->
                        String.join("\n", workspaceTools.listWorkspaceFiles(
                                args.path("glob").asText("**/*"), args.path("maxResults").asInt(100))));
        registerTool("search_workspace", "Searches workspace files for a literal string, returning file:line matches.",
                schema(Map.of("query", "string", "maxResults", "integer")), args ->
                        String.join("\n", workspaceTools.searchWorkspace(
                                args.path("query").asText(), args.path("maxResults").asInt(100))));
        registerTool("remember_context", "Persists an architectural insight, decision, or finding to shared workspace memory (.cast/memory/).",
                schema(Map.of("topic", "string", "insight", "string", "author", "string")), args ->
                        memoryTools.rememberContext(args.path("topic").asText(), args.path("insight").asText(), args.path("author").asText("Agent")));
        registerTool("recall_context", "Recalls workspace decisions and insights matching a query from shared memory (.cast/memory/).",
                schema(Map.of("query", "string", "maxResults", "integer")), args ->
                        memoryTools.recallContext(args.path("query").asText(""), args.path("maxResults").asInt(10)));
        registerTool("summarize_session", "Summarizes session actions using the local LLM in the background and saves to long-term memory.",
                schema(Map.of("sessionId", "string", "agentRole", "string", "actionsContent", "string")), args ->
                        memoryTools.summarizeSession(
                                args.path("sessionId").asText("default-session"),
                                args.path("agentRole").asText("Agent"),
                                args.path("actionsContent").asText("")));
        registerTool("recall_session_memory", "Recalls long-term session summaries and turnover context across sessions and agents.",
                schema(Map.of("query", "string", "maxResults", "integer")), args ->
                        memoryTools.recallSessionMemory(args.path("query").asText(""), args.path("maxResults").asInt(10)));

        if (config.embeddings().enabled()) {
            WorkspaceEmbeddingIndex embeddingIndex = new WorkspaceEmbeddingIndex(
                    config.embeddings(), Path.of(config.tools().workspaceRoot()),
                    new EmbeddingModelFactory().create(config.embeddings()));
            registerTool("semantic_search_workspace",
                    "Semantically searches an embedding index of the workspace for code/text relevant "
                            + "to a natural-language query, returning file:line matches with relevance scores.",
                    schema(Map.of("query", "string", "maxResults", "integer")), args ->
                            String.join("\n\n", new SemanticSearchTools(embeddingIndex)
                                    .semanticSearchWorkspace(args.path("query").asText(), args.path("maxResults").asInt(10))));
        }
    }

    private static HarnessConfig restrictToCheapTiers(HarnessConfig config) {
        List<ProviderConfig> cheapProviders = config.providers().stream()
                .filter(p -> p.tier() != ModelTier.FRONTIER_CLOUD)
                .toList();
        if (cheapProviders.isEmpty()) {
            throw new IllegalStateException(
                    "mcp-serve requires at least one enabled SMALL_LOCAL or LARGE_LOCAL provider; "
                            + "this server intentionally never offloads to FRONTIER_CLOUD");
        }
        // The MCP server has no interactive stdin/stdout of its own to run a ConsoleApprovalGate on
        // (those streams are the JSON-RPC channel), so it forces writes and process exec off entirely
        // regardless of what the caller's config allows, rather than defaulting to auto-approval.
        ToolConfig readOnlyTools = new ToolConfig(
                config.tools().workspaceRoot(), config.tools().maxFileBytes(), config.tools().jshellEnabled(),
                false, false, true);
        ReliabilityConfig reliability = config.reliability();
        ReliabilityConfig mcpReliability = new ReliabilityConfig(
                1, reliability.initialBackoffMillis(), reliability.maxBackoffMillis(),
                reliability.failureThreshold(), reliability.cooldownSeconds(),
                reliability.mcpDelegationDeadlineSeconds(),
                reliability.maxConcurrentRequests(), reliability.fallbackOrder(),
                reliability.maxCostUsdPerTask(), reliability.maxCumulativeCostUsd(),
                reliability.maxRequestsPerMinute(), reliability.mcpDelegationDeadlineSeconds());
        return new HarnessConfig(cheapProviders, config.routing(), readOnlyTools, config.mcpServers(),
                config.embeddings(), config.memory(), mcpReliability, config.observability(), config.mcpAudit());
    }

    private static ModelTier preferredTier(HarnessConfig config, ModelTier preferred) {
        return config.providers().stream().anyMatch(provider -> provider.tier() == preferred)
                ? preferred : config.providers().getFirst().tier();
    }

    private void registerAskLocal(HarnessOrchestrator cheapOrchestrator, int maxContextChars,
                                  HarnessConfig cheapOnlyConfig) {
        registerRichTool("ask_local",
                "Generic bounded delegation to cheap local/small model tiers (never frontier cloud). "
                        + "Prefer the structured delegation tools when one matches the task. "
                        + "Valid workloads: AUTO, QUICK, CODE, REASONING.",
                schema(Map.of("prompt", "string", "workload", "workload"), List.of("prompt")),
                args -> {
                    String prompt = args.path("prompt").asText("");
                    if (prompt.isBlank()) {
                        throw new IllegalArgumentException("prompt must not be blank");
                    }
                    if (prompt.length() > maxContextChars) throw new IllegalArgumentException(
                            "prompt exceeds configured maximum of " + maxContextChars + " characters");
                    Workload workload = parseWorkload(args.path("workload").asText("AUTO"));
                    ModelTier tier = workload == Workload.QUICK
                            ? preferredTier(cheapOnlyConfig, ModelTier.SMALL_LOCAL)
                            : preferredTier(cheapOnlyConfig, ModelTier.LARGE_LOCAL);
                    HarnessOrchestrator.Outcome outcome = cheapOrchestrator.run(
                            new TaskRequest(prompt, workload, tier, true));
                    return new McpTool.ExecutionResult(outcome.answer(), new McpTool.Delegation(
                            outcome.traceId(), outcome.provider().id(), outcome.provider().tier().name(),
                            outcome.provider().modelName(), outcome.inputTokens(), outcome.outputTokens(),
                            outcome.estimatedCostUsd(), telemetry.promptHash(prompt), prompt.length(), outcome.durationMs()));
                });
    }

    private void registerStructuredDelegationTools(ReadOnlyDelegationTools delegation) {
        registerRichTool("summarize_files",
                "Summarizes caller-selected workspace files with path citations. Read-only and bounded.",
                schema(Map.of("paths", "string[]", "question", "string"), List.of("paths")),
                delegation::summarizeFiles);
        registerRichTool("analyze_failure",
                "Performs first-pass triage of a log, stack trace, or test failure. Not final diagnosis.",
                schema(Map.of("log", "string", "question", "string", "relevantPaths", "string[]"), List.of("log")),
                delegation::analyzeFailure);
        registerRichTool("draft_patch",
                "Drafts a unified diff from selected workspace files without applying it.",
                schema(Map.of("request", "string", "paths", "string[]"), List.of("request", "paths")),
                delegation::draftPatch);
        registerRichTool("generate_tests",
                "Drafts focused tests for one target file without writing files or running tests.",
                schema(Map.of("targetPath", "string", "request", "string", "relatedPaths", "string[]"),
                        List.of("targetPath", "request")),
                delegation::generateTests);
        registerRichTool("review_diff",
                "Performs first-pass non-security review of a diff, without final correctness approval.",
                schema(Map.of("diff", "string", "focus", "string"), List.of("diff")),
                delegation::reviewDiff);
        registerRichTool("map_change_impact",
                "Maps likely impact for a symbol using bounded literal repository search.",
                schema(Map.of("symbol", "string", "question", "string", "maxResults", "integer"), List.of("symbol")),
                delegation::mapChangeImpact);
    }

    private void registerListModels(HarnessConfig cheapOnlyConfig) {
        registerTool("list_models",
                "Lists the cheap local/small model tiers available to ask_local on this harness.",
                schema(Map.of()),
                args -> {
                    StringBuilder sb = new StringBuilder();
                    for (ProviderConfig provider : cheapOnlyConfig.providers()) {
                        sb.append(provider.id()).append(" [").append(provider.tier()).append("] model=")
                                .append(provider.modelName()).append(" enabled=").append(provider.enabled())
                                .append(" credentials=").append(provider.credentialsAvailable() ? "ready" : "missing")
                                .append('\n');
                    }
                    return sb.toString();
                });
    }

    private static Workload parseWorkload(String value) {
        if (value == null || value.isBlank()) return Workload.AUTO;
        try {
            return Workload.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "workload must be one of AUTO, QUICK, CODE, or REASONING; received: " + value);
        }
    }

    private void registerTool(String name, String description, ObjectNode inputSchema, TextHandler handler) {
        registerRichTool(name, description, inputSchema, args -> McpTool.ExecutionResult.text(handler.handle(args)));
    }

    private void registerRichTool(String name, String description, ObjectNode inputSchema, McpTool.Handler handler) {
        tools.put(name, new McpTool(name, description, inputSchema, handler));
    }

    private boolean suppressDuplicateFailure(String key) {
        long now = System.currentTimeMillis();
        recentFailedDelegations.entrySet().removeIf(
                entry -> now - entry.getValue() >= FAILED_REQUEST_TTL_MILLIS);
        Long failedAt = recentFailedDelegations.get(key);
        return failedAt != null && now - failedAt < FAILED_REQUEST_TTL_MILLIS;
    }

    private void addFailureMetadata(ObjectNode result, String invocationId, String errorType,
                                    boolean retrySuppressed) {
        ObjectNode metadata = result.withObject("_meta");
        metadata.put("castcli/invocationId", invocationId);
        metadata.put("castcli/usageAuditPath", usageStore.path().toString());
        metadata.put("castcli/errorType", errorType);
        metadata.put("castcli/fallbackRecommended", true);
        metadata.put("castcli/retrySuppressed", retrySuppressed);
    }

    private static String classifyError(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String type = current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (type.contains("timeout") || message.contains("timed out") || message.contains("deadline")) {
                return "timeout";
            }
            if (message.contains("context") || message.contains("prompt budget")
                    || message.contains("configured maximum") || message.contains("exceeds configured")) {
                return "context_rejected";
            }
        }
        return failure.getClass().getName();
    }

    private static String boundedText(String text, int maximum) {
        if (text == null || text.length() <= maximum) return text == null ? "" : text;
        String marker = "\n...[MCP result truncated by output budget]...\n";
        int head = Math.max(0, (maximum - marker.length()) / 2);
        int tail = Math.max(0, maximum - marker.length() - head);
        if (head + tail == 0) return text.substring(0, maximum);
        return text.substring(0, head) + marker + text.substring(text.length() - tail);
    }

    private void appendUsage(McpUsageRecord record) {
        if (!auditEnabled) return;
        try {
            usageStore.append(record);
        } catch (RuntimeException e) {
            log.warn("Failed to persist MCP usage audit record: {}", e.getMessage());
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static McpUsageRecord toUsageRecord(
            long startedEpochMs, long startedNanos, String invocationId, String toolName, String text,
            McpTool.Delegation delegation, String fallbackTraceId, String callerModel) {
        if (delegation == null) {
            return new McpUsageRecord(startedEpochMs, invocationId, fallbackTraceId, toolName, true,
                    elapsedMillis(startedNanos), null, null, null, 0, 0, 0, null, 0, text.length(), null, callerModel);
        }
        return new McpUsageRecord(startedEpochMs, invocationId, delegation.traceId(), toolName, true,
                elapsedMillis(startedNanos), delegation.providerId(), delegation.providerTier(), delegation.modelName(),
                delegation.inputTokens(), delegation.outputTokens(), delegation.estimatedCostUsd(),
                delegation.promptSha256(), delegation.promptChars(), text.length(), null, callerModel);
    }

    private static void addDelegationMetadata(ObjectNode metadata, McpTool.Delegation delegation) {
        metadata.put("castcli/traceId", delegation.traceId());
        metadata.put("castcli/provider", delegation.providerId());
        metadata.put("castcli/providerTier", delegation.providerTier());
        metadata.put("castcli/model", delegation.modelName());
        metadata.put("castcli/inputTokens", delegation.inputTokens());
        metadata.put("castcli/outputTokens", delegation.outputTokens());
        metadata.put("castcli/estimatedCostUsd", delegation.estimatedCostUsd());
        metadata.put("castcli/durationMs", delegation.modelDurationMs());
    }

    private static String delegationReceipt(String invocationId, McpTool.Delegation delegation) {
        return "\n\n[CastCLI delegation " + invocationId + ": " + delegation.providerId() + "/"
                + delegation.modelName() + ", " + (delegation.inputTokens() + delegation.outputTokens())
                + " local tokens, est. $" + String.format(java.util.Locale.ROOT, "%.5f", delegation.estimatedCostUsd())
                + ", trace " + delegation.traceId() + "]";
    }

    @FunctionalInterface
    private interface TextHandler {
        String handle(JsonNode arguments) throws Exception;
    }

    private ObjectNode schema(Map<String, String> properties) {
        return schema(properties, List.of());
    }

    private ObjectNode schema(Map<String, String> properties, List<String> required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            ObjectNode property = props.putObject(entry.getKey());
            if ("string[]".equals(entry.getValue())) {
                property.put("type", "array").putObject("items").put("type", "string");
            } else if ("workload".equals(entry.getValue())) {
                property.put("type", "string");
                ArrayNode values = property.putArray("enum");
                for (Workload workload : Workload.values()) values.add(workload.name());
                property.put("default", Workload.AUTO.name());
            } else {
                property.put("type", entry.getValue());
            }
        }
        ArrayNode requiredProperties = schema.putArray("required");
        required.forEach(requiredProperties::add);
        schema.put("additionalProperties", false);
        return schema;
    }

    @SuppressWarnings("serial")
    private static final class McpProtocolException extends RuntimeException {
        final int code;

        McpProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static boolean isCacheableTool(String toolName) {
        return switch (toolName) {
            case "summarize_files", "analyze_failure", "review_diff",
                    "map_change_impact", "generate_tests", "search_workspace",
                    "read_workspace_file", "list_workspace_files" -> true;
            default -> false;
        };
    }

    private static boolean isDelegationTool(String toolName) {
        return switch (toolName) {
            case "ask_local", "summarize_files", "analyze_failure", "draft_patch",
                    "generate_tests", "review_diff", "map_change_impact" -> true;
            default -> false;
        };
    }

    private static String extractCallerModel(JsonNode params) {
        if (params != null) {
            JsonNode meta = params.path("_meta");
            if (meta.hasNonNull("callerModel")) {
                return meta.path("callerModel").asText();
            }
            JsonNode args = params.path("arguments");
            if (args != null && args.isObject()) {
                if (args.path("_meta").hasNonNull("callerModel")) {
                    return args.path("_meta").path("callerModel").asText();
                }
                if (args.hasNonNull("callerModel")) {
                    return args.path("callerModel").asText();
                }
            }
        }
        String sysProp = System.getProperty("castcli.callerModel");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        String envVar = System.getenv("CASTCLI_CALLER_MODEL");
        if (envVar != null && !envVar.isBlank()) {
            return envVar.trim();
        }
        return null;
    }
}
