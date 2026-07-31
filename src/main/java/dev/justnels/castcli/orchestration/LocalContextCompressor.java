package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.ModelTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dense log and context compressor using local models (SMALL_LOCAL tier)
 * to prevent context-window overflow when passing tool outputs or build logs.
 */
public final class LocalContextCompressor {

    private static final int DEFAULT_MAX_CHARS = 2_000;
    private final HarnessOrchestrator orchestrator;

    public LocalContextCompressor(HarnessOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public String compress(String rawText) {
        return compress(rawText, DEFAULT_MAX_CHARS);
    }

    public String compress(String rawText, int maxTargetChars) {
        if (rawText == null || rawText.isBlank() || rawText.length() <= maxTargetChars) {
            return rawText == null ? "" : rawText;
        }

        if (orchestrator != null) {
            try {
                String prompt = buildCompressionPrompt(rawText, maxTargetChars);
                HarnessOrchestrator.Outcome outcome = orchestrator.run(
                        new TaskRequest(prompt, Workload.QUICK, ModelTier.SMALL_LOCAL));
                if (outcome != null && outcome.answer() != null && !outcome.answer().isBlank()) {
                    return outcome.answer().trim();
                }
            } catch (Exception ignored) {
                // Fallback to heuristic compression
            }
        }

        return fallbackCompress(rawText, maxTargetChars);
    }

    private static String buildCompressionPrompt(String rawText, int maxTargetChars) {
        String truncated = rawText.length() > 6_000 ? rawText.substring(0, 6_000) + "\n...[truncated]" : rawText;
        return "You are a log and diagnostic context compressor. "
                + "Compress the following build output/log into a concise summary under " + maxTargetChars + " characters.\n"
                + "Focus on:\n"
                + "1. Primary Error / Outcome Cause\n"
                + "2. Critical Exception/Line references\n"
                + "3. Key Actionable Remediation Points\n\n"
                + "Raw Log:\n" + truncated;
    }

    private static String fallbackCompress(String text, int maxTargetChars) {
        String[] lines = text.split("\r?\n");
        List<String> keyLines = new ArrayList<>();

        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("fail") || lower.contains("exception")
                    || lower.contains("cause") || lower.contains("warning") || lower.startsWith("> task")) {
                keyLines.add(line);
            }
        }

        StringBuilder sb = new StringBuilder("[Compressed Log Summary]\n");
        for (String line : keyLines) {
            if (sb.length() + line.length() + 1 > maxTargetChars - 100) break;
            sb.append(line).append("\n");
        }

        if (sb.length() <= 30 && lines.length > 0) {
            // Keep head and tail lines if no keywords matched
            int headCount = Math.min(10, lines.length);
            for (int i = 0; i < headCount; i++) {
                sb.append(lines[i]).append("\n");
            }
            sb.append("...[lines truncated]...\n");
            int tailStart = Math.max(headCount, lines.length - 10);
            for (int i = tailStart; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
