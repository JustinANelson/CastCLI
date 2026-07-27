package dev.justnels.castcli.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sandboxing security guard for process execution.
 * Validates working directories, sanitizes process environment variables, and enforces safety bounds.
 */
public class ProcessSandboxGuard {
    private static final Set<String> SENSITIVE_ENV_KEYS = Set.of(
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "AWS_SECRET_ACCESS_KEY",
            "AWS_SESSION_TOKEN", "GITHUB_TOKEN", "SLACK_BOT_TOKEN", "DATABASE_PASSWORD"
    );

    private final Path rootDirectory;
    private final long timeoutSeconds;
    private final int maxOutputChars;

    public ProcessSandboxGuard(Path rootDirectory, long timeoutSeconds, int maxOutputChars) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 120;
        this.maxOutputChars = maxOutputChars > 0 ? maxOutputChars : 8_000;
    }

    public ProcessSandboxGuard(Path rootDirectory) {
        this(rootDirectory, 120, 8_000);
    }

    /**
     * Validates that the target working directory is confined within the authorized root directory.
     */
    public Path validateWorkingDirectory(Path targetDir) {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(rootDirectory)) {
            throw new SecurityException(String.format("Working directory '%s' is outside authorized sandbox root '%s'",
                    normalizedTarget, rootDirectory));
        }
        return normalizedTarget;
    }

    /**
     * Validates that command tokens do not contain dangerous shell injection constructs.
     */
    public void validateCommandTokens(List<String> commandTokens) {
        if (commandTokens == null || commandTokens.isEmpty()) {
            throw new IllegalArgumentException("Command tokens must not be empty.");
        }
        for (String token : commandTokens) {
            if (token.contains(";") || token.contains("&&") || token.contains("||") || token.contains("`") || token.contains("\n")) {
                throw new SecurityException("Command token contains illegal shell control character: " + token);
            }
        }
    }

    /**
     * Sanitizes process builder environment variables by removing sensitive environment secrets.
     */
    public Map<String, String> sanitizeEnvironment(Map<String, String> originalEnv) {
        Map<String, String> sanitized = new java.util.HashMap<>(originalEnv);
        for (String key : SENSITIVE_ENV_KEYS) {
            sanitized.remove(key);
        }
        return Map.copyOf(sanitized);
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }
}
