package dev.justnels.castcli.tools;

import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.TaskRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FastPathExecutor {
    public record FastPathResult(String answer, String toolUsed) {}

    private static final Pattern FILE_LIST_PATTERN = Pattern.compile(
            "list(?: workspace| repo)? files matching\\s+([^\\s]+)", Pattern.CASE_INSENSITIVE);

    public Optional<FastPathResult> executeIfPossible(TaskRequest task, ToolConfig config) {
        String prompt = task.prompt().trim();
        String lower = prompt.toLowerCase(Locale.ROOT);

        if (isPureTimeQuery(lower)) {
            SystemTools systemTools = new SystemTools();
            String timeStr = systemTools.currentTime("UTC");
            return Optional.of(new FastPathResult("Current UTC time is " + timeStr, "currentTime"));
        }

        Matcher matcher = FILE_LIST_PATTERN.matcher(prompt);
        if (matcher.find()) {
            String glob = matcher.group(1);
            try {
                WorkspaceTools wsTools = new WorkspaceTools(Path.of(config.workspaceRoot()), config.maxFileBytes());
                List<String> files = wsTools.listWorkspaceFiles(glob, 100);
                String answer = files.isEmpty()
                        ? "No files matched pattern: " + glob
                        : "Matched files:\n" + String.join("\n", files);
                return Optional.of(new FastPathResult(answer, "listWorkspaceFiles"));
            } catch (Exception ignored) {
                // Fall back to LLM processing if glob parsing fails
            }
        }

        return Optional.empty();
    }

    private static boolean isPureTimeQuery(String lowerPrompt) {
        return lowerPrompt.equals("what time is it?")
                || lowerPrompt.equals("what time is it")
                || lowerPrompt.equals("what is the current time?")
                || lowerPrompt.equals("what is the current time")
                || lowerPrompt.equals("current time");
    }
}

