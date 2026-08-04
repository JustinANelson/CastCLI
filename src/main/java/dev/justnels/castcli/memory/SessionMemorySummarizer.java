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

        // Build the deterministic, purely-templated extractive log first: this is the ground truth the
        // LLM is asked to condense, not an independent source of facts, so it cannot introduce a claim
        // that isn't literally present in it. If no orchestrator is available or condensation fails,
        // this extractive text is what gets stored -- so a hallucination-prone step is never the only
        // path to a persisted memory.
        String extractiveLog = buildExtractiveLog(effectiveSessionId, effectiveRole, safeActions);

        String summaryContent = extractiveLog;
        double confidence = 0.95;
        if (orchestrator != null && !safeActions.isEmpty()) {
            String prompt = buildCondensationPrompt(effectiveSessionId, effectiveRole, extractiveLog);
            try {
                // LARGE_LOCAL, not SMALL_LOCAL: this call is async and off the interactive critical path,
                // so there's no reason to pay for hallucination risk to save a few seconds of latency.
                HarnessOrchestrator.Outcome outcome = orchestrator.run(
                        new TaskRequest(prompt, Workload.QUICK, ModelTier.LARGE_LOCAL, false, true));
                if (outcome != null && outcome.answer() != null && !outcome.answer().isBlank()) {
                    summaryContent = outcome.answer().trim();
                    // Lower than the deterministic extractive log's 0.95: this text is model-condensed,
                    // so downstream readers (see MemoryContextProvider) should verify surprising claims
                    // against the actual code/config rather than treat it as ground truth.
                    confidence = 0.75;
                }
            } catch (Exception e) {
                // Keep the deterministic extractive log as summaryContent.
            }
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
                confidence,
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

    /** Purely templated, non-LLM rendering of the actions log. Doubles as both the grounding source
     * text fed to the condensation prompt below and the stored content when no orchestrator is
     * available or condensation fails -- so the only fact-generating step here is deterministic. */
    private static String buildExtractiveLog(String sessionId, String agentRole, List<SessionAction> actions) {
        if (actions.isEmpty()) {
            return "Session Summary [" + sessionId + "]: No actions recorded during this session by " + agentRole + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Session Turnover Summary [Session: ").append(sessionId).append(", Agent: ").append(agentRole).append("]\n");
        sb.append("Recorded Actions (").append(actions.size()).append(" total):\n");
        for (SessionAction act : actions) {
            sb.append("- [").append(act.agentRole()).append("] ").append(act.action());
            if (act.details() != null && !act.details().isBlank()) {
                sb.append(" -> ").append(act.details());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** Asks the model to reorganize/condense the already-accurate extractive log into a readable
     * report -- never to independently recall or infer what happened -- to keep the LLM's role
     * restricted to formatting rather than fact generation. */
    private static String buildCondensationPrompt(String sessionId, String agentRole, String extractiveLog) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a local session memory summarizer for an AI agent system.\n");
        sb.append("Below is a VERBATIM, already-accurate extractive log of everything that happened in this session. ");
        sb.append("Reorganize and condense it into a structured turnover note for future agents. ");
        sb.append("Do not summarize from memory or prior knowledge -- summarize only the log below.\n\n");
        sb.append("STRICT RULES:\n");
        sb.append("- Only state facts that literally appear in the log below. Never infer, assume, guess, ")
                .append("or add any action, change, or outcome that is not explicitly present in it.\n");
        sb.append("- Do not describe anything as done, enabled, fixed, completed, or changed unless the log explicitly says so.\n");
        sb.append("- If something is ambiguous or missing from the log, omit it rather than filling the gap.\n\n");
        sb.append("Session ID: ").append(sessionId).append("\n");
        sb.append("Agent Role: ").append(agentRole).append("\n\n");
        sb.append("VERBATIM LOG (ground truth -- do not contradict or add to this):\n").append(extractiveLog).append("\n\n");
        sb.append("Reformat the log above into:\n");
        sb.append("1. High-Level Objective & Summary\n");
        sb.append("2. Key Decisions & Accomplishments\n");
        sb.append("3. Pending / Next Steps for future agents\n");
        return sb.toString();
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
