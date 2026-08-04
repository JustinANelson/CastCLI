package dev.justnels.castcli.memory;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Autonomous background memory cleaner that deduplicates, consolidates, and purges
 * stale session memory records using local LLM summarization.
 */
public final class LocalMemoryCleaner {

    public record CleaningReport(int totalInspected, int entriesConsolidated, int entriesPurged) {}

    private final MemoryStore store;
    private final HarnessOrchestrator orchestrator;
    private final String namespace;

    public LocalMemoryCleaner(MemoryStore store, HarnessOrchestrator orchestrator) {
        this(store, orchestrator, "session");
    }

    public LocalMemoryCleaner(MemoryStore store, HarnessOrchestrator orchestrator, String namespace) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.orchestrator = orchestrator;
        this.namespace = (namespace == null || namespace.isBlank()) ? "session" : namespace.trim();
    }

    /** Scans, deduplicates, and consolidates session memories. */
    public CleaningReport cleanAndConsolidate() {
        List<MemoryEntry> entries = store.list(namespace, 100);
        if (entries.isEmpty()) {
            return new CleaningReport(0, 0, 0);
        }

        int expiredPurged = store.purgeExpired();
        Map<String, List<MemoryEntry>> groupedByTopic = new HashMap<>();

        for (MemoryEntry entry : entries) {
            String groupKey = extractGroupKey(entry.topic());
            groupedByTopic.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(entry);
        }

        int consolidatedCount = 0;
        int purgedCount = expiredPurged;

        for (Map.Entry<String, List<MemoryEntry>> group : groupedByTopic.entrySet()) {
            List<MemoryEntry> groupEntries = group.getValue();
            if (groupEntries.size() > 1) {
                MemoryEntry consolidated = consolidateGroup(group.getKey(), groupEntries);
                if (consolidated != null) {
                    consolidatedCount++;
                    for (MemoryEntry oldEntry : groupEntries) {
                        if (!oldEntry.id().equals(consolidated.id()) && !oldEntry.readOnly()) {
                            if (store.delete(oldEntry.id(), oldEntry.version())) {
                                purgedCount++;
                            }
                        }
                    }
                }
            }
        }

        return new CleaningReport(entries.size(), consolidatedCount, purgedCount);
    }

    private MemoryEntry consolidateGroup(String groupKey, List<MemoryEntry> groupEntries) {
        // Deterministic extractive text first: this is the ground truth the LLM is asked to condense,
        // so it's also what gets stored if condensation is unavailable or fails.
        String extractive = buildFallbackConsolidatedContent(groupEntries);
        String consolidatedContent = extractive;
        double confidence = 0.95;
        if (orchestrator != null) {
            String prompt = buildConsolidationPrompt(groupKey, extractive);
            try {
                // LARGE_LOCAL, not SMALL_LOCAL: background consolidation isn't latency-sensitive, so
                // there's no reason to pay for the smaller model's higher hallucination risk.
                HarnessOrchestrator.Outcome outcome = orchestrator.run(
                        new TaskRequest(prompt, Workload.QUICK, ModelTier.LARGE_LOCAL, false, true));
                if (outcome != null && outcome.answer() != null && !outcome.answer().isBlank()) {
                    consolidatedContent = outcome.answer().trim();
                    // Lower than the extractive text's 0.95: model-condensed, verify surprising claims.
                    confidence = 0.75;
                }
            } catch (Exception e) {
                // Keep the deterministic extractive text as consolidatedContent.
            }
        }

        MemoryDraft draft = new MemoryDraft(
                namespace,
                "session-turnover",
                "consolidated:" + groupKey,
                consolidatedContent,
                "LocalMemoryCleaner",
                "memory-cleaner",
                List.of("session-summary", "consolidated", "turnover"),
                0.85,
                confidence,
                null,
                false,
                null
        );

        return store.put(draft);
    }

    private static String extractGroupKey(String topic) {
        if (topic == null) return "general";
        if (topic.startsWith("session-summary:")) {
            return topic.substring("session-summary:".length());
        }
        int colonIdx = topic.indexOf(':');
        return colonIdx > 0 ? topic.substring(0, colonIdx) : topic;
    }

    private static String buildConsolidationPrompt(String groupKey, String extractiveRecords) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a local memory consolidation assistant.\n");
        sb.append("Below are VERBATIM, already-accurate session memory records for one group. ");
        sb.append("Deduplicate and combine them into a single concise long-term summary. ");
        sb.append("Do not summarize from memory or prior knowledge -- summarize only the records below.\n\n");
        sb.append("STRICT RULES:\n");
        sb.append("- Only state facts that literally appear in the records below. Never infer, assume, guess, ")
                .append("or add any accomplishment, decision, or next step that is not explicitly present in them.\n");
        sb.append("- If two records conflict, keep both statements rather than guessing which is correct.\n\n");
        sb.append("Group Key: ").append(groupKey).append("\n\n");
        sb.append("VERBATIM RECORDS (ground truth -- do not contradict or add to this):\n").append(extractiveRecords).append("\n\n");
        sb.append("Produce a unified, structured summary of key accomplishments, decisions, and active next steps.");
        return sb.toString();
    }

    private static String buildFallbackConsolidatedContent(List<MemoryEntry> groupEntries) {
        return groupEntries.stream()
                .map(MemoryEntry::content)
                .distinct()
                .collect(Collectors.joining("\n---\n"));
    }
}
