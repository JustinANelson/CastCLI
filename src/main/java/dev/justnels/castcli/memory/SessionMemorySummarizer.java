package dev.justnels.castcli.memory;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Summarizes recorded session actions using the local LLM in the background
 * and persists the resulting turnover summary into the long-term memory store.
 */
public final class SessionMemorySummarizer implements AutoCloseable {

    private final MemoryStore memoryStore;
    private final HarnessOrchestrator orchestrator;
    private final String namespace;
    private final ExecutorService executor;

    public SessionMemorySummarizer(MemoryStore memoryStore, HarnessOrchestrator orchestrator) {
        this(memoryStore, orchestrator, "session");
    }

    public SessionMemorySummarizer(MemoryStore memoryStore, HarnessOrchestrator orchestrator, String namespace) {
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore must not be null");
        this.orchestrator = orchestrator;
        this.namespace = (namespace == null || namespace.isBlank()) ? "session" : namespace.trim();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "castcli-session-summarizer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Asynchronously summarizes session actions and persists to durable long-term memory. */
    public CompletableFuture<MemoryEntry> summarizeSessionAsync(String sessionId, String agentRole, List<SessionAction> actions) {
        return CompletableFuture.supplyAsync(() -> summarizeSession(sessionId, agentRole, actions), executor);
    }

    /** Synchronously summarizes session actions and persists to durable long-term memory. */
    public MemoryEntry summarizeSession(String sessionId, String agentRole, List<SessionAction> actions) {
        String effectiveSessionId = (sessionId == null || sessionId.isBlank()) ? "default-session" : sessionId.trim();
        String effectiveRole = (agentRole == null || agentRole.isBlank()) ? "Agent" : agentRole.trim();
        List<SessionAction> safeActions = actions == null ? List.of() : List.copyOf(actions);

        String summaryContent;
        if (orchestrator != null && !safeActions.isEmpty()) {
            String prompt = buildPrompt(effectiveSessionId, effectiveRole, safeActions);
            try {
                HarnessOrchestrator.Outcome outcome = orchestrator.run(
                        new TaskRequest(prompt, Workload.QUICK, ModelTier.SMALL_LOCAL));
                summaryContent = (outcome != null && outcome.answer() != null && !outcome.answer().isBlank())
                        ? outcome.answer().trim()
                        : buildFallbackSummary(effectiveSessionId, effectiveRole, safeActions);
            } catch (Exception e) {
                summaryContent = buildFallbackSummary(effectiveSessionId, effectiveRole, safeActions);
            }
        } else {
            summaryContent = buildFallbackSummary(effectiveSessionId, effectiveRole, safeActions);
        }

        MemoryDraft draft = new MemoryDraft(
                namespace,
                "session-turnover",
                "session-summary:" + effectiveSessionId,
                summaryContent,
                effectiveRole,
                "session-summarizer",
                List.of("session-summary", "turnover", effectiveRole.toLowerCase(Locale.ROOT)),
                0.8,
                0.9,
                null,
                false,
                null
        );

        return memoryStore.put(draft);
    }

    /** Recalls session turnover summaries from the long-term memory store. */
    public List<MemoryEntry> recallSessionMemory(String query, int maxResults) {
        String effectiveQuery = (query == null) ? "" : query.trim();
        int limit = maxResults <= 0 ? 10 : maxResults;
        return memoryStore.search(MemoryQuery.inNamespace(effectiveQuery, namespace, limit));
    }

    private static String buildPrompt(String sessionId, String agentRole, List<SessionAction> actions) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a local session memory summarizer for an AI agent system. ");
        sb.append("Summarize the following session actions for long-term memory and future agent turnover.\n");
        sb.append("Session ID: ").append(sessionId).append("\n");
        sb.append("Agent Role: ").append(agentRole).append("\n\n");
        sb.append("Actions Log:\n");
        for (SessionAction act : actions) {
            sb.append("- [").append(act.agentRole()).append("] ").append(act.action());
            if (act.details() != null && !act.details().isBlank()) {
                sb.append(": ").append(act.details());
            }
            sb.append("\n");
        }
        sb.append("\nPlease format your response cleanly with:\n");
        sb.append("1. High-Level Objective & Summary\n");
        sb.append("2. Key Decisions & Accomplishments\n");
        sb.append("3. Pending / Next Steps for future agents\n");
        return sb.toString();
    }

    private static String buildFallbackSummary(String sessionId, String agentRole, List<SessionAction> actions) {
        if (actions.isEmpty()) {
            return "Session Summary [" + sessionId + "]: No actions recorded during this session by " + agentRole + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Session Turnover Summary [Session: ").append(sessionId).append(", Agent: ").append(agentRole).append("]\n");
        sb.append("Recorded Actions (").append(actions.size()).append(" total):\n");
        for (SessionAction act : actions) {
            sb.append("- [").append(act.agentRole()).append("] ").append(act.action());
            if (act.details() != null && !act.details().isBlank()) {
                String detailSnippet = act.details().length() > 120 ? act.details().substring(0, 120) + "..." : act.details();
                sb.append(" -> ").append(detailSnippet);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
