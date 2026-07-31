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
        String consolidatedContent;
        if (orchestrator != null) {
            String prompt = buildConsolidationPrompt(groupKey, groupEntries);
            try {
                HarnessOrchestrator.Outcome outcome = orchestrator.run(
                        new TaskRequest(prompt, Workload.QUICK, ModelTier.SMALL_LOCAL));
                consolidatedContent = (outcome != null && outcome.answer() != null && !outcome.answer().isBlank())
                        ? outcome.answer().trim()
                        : buildFallbackConsolidatedContent(groupEntries);
            } catch (Exception e) {
                consolidatedContent = buildFallbackConsolidatedContent(groupEntries);
            }
        } else {
            consolidatedContent = buildFallbackConsolidatedContent(groupEntries);
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
                0.95,
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

    private static String buildConsolidationPrompt(String groupKey, List<MemoryEntry> groupEntries) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a local memory consolidation assistant. ");
        sb.append("Deduplicate and combine the following session memory records into a single concise long-term summary.\n");
        sb.append("Group Key: ").append(groupKey).append("\n\n");
        sb.append("Records:\n");
        for (MemoryEntry entry : groupEntries) {
            sb.append("- [").append(entry.topic()).append("] ").append(entry.content()).append("\n");
        }
        sb.append("\nProduce a unified, structured summary of key accomplishments, decisions, and active next steps.");
        return sb.toString();
    }

    private static String buildFallbackConsolidatedContent(List<MemoryEntry> groupEntries) {
        return groupEntries.stream()
                .map(MemoryEntry::content)
                .distinct()
                .collect(Collectors.joining("\n---\n"));
    }
}
