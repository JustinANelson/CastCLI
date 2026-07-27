package dev.justnels.castcli.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Sandboxing security guard for process execution.
 * Validates working directories, sanitizes process environment variables, and enforces safety bounds.
 */
public class ProcessSandboxGuard {
    private static final Set<String> ALLOWED_ENV_KEYS = Set.of(
            "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC",
            "JAVA_HOME", "GRADLE_USER_HOME", "HOME", "USERPROFILE",
            "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "TERM"
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
     * Builds a minimal child environment. An allow-list is intentional: secret names are not
     * enumerable, and provider-specific credentials must never reach repository-controlled builds.
     */
    public Map<String, String> sanitizeEnvironment(Map<String, String> originalEnv) {
        Map<String, String> sanitized = new HashMap<>();
        for (Map.Entry<String, String> entry : originalEnv.entrySet()) {
            String normalized = entry.getKey().toUpperCase(Locale.ROOT);
            if (ALLOWED_ENV_KEYS.contains(normalized) || normalized.startsWith("LC_")) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(sanitized);
    }

    /** Applies the minimal environment to a mutable {@link ProcessBuilder#environment()} map. */
    public void applySanitizedEnvironment(Map<String, String> processEnvironment) {
        Map<String, String> sanitized = sanitizeEnvironment(processEnvironment);
        processEnvironment.clear();
        processEnvironment.putAll(sanitized);
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }
}
