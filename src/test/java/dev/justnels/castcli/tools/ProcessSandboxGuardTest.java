package dev.justnels.castcli.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessSandboxGuardTest {

    @Test
    @DisplayName("Validates working directory within sandbox root")
    void validatesWorkingDirectoryWithinRoot(@TempDir Path tempDir) {
        ProcessSandboxGuard guard = new ProcessSandboxGuard(tempDir);
        Path subDir = tempDir.resolve("subdir");

        Path validated = guard.validateWorkingDirectory(subDir);
        assertThat(validated).isEqualTo(subDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Throws SecurityException for working directory outside sandbox root")
    void throwsForDirectoryOutsideRoot(@TempDir Path tempDir) {
        ProcessSandboxGuard guard = new ProcessSandboxGuard(tempDir.resolve("allowed"));

        assertThatThrownBy(() -> guard.validateWorkingDirectory(tempDir.resolve("other")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside authorized sandbox root");
    }

    @Test
    @DisplayName("Validates clean command tokens")
    void validatesCleanCommandTokens(@TempDir Path tempDir) {
        ProcessSandboxGuard guard = new ProcessSandboxGuard(tempDir);
        guard.validateCommandTokens(List.of("git", "status", "--short"));
    }

    @Test
    @DisplayName("Rejects command tokens with shell control characters")
    void rejectsIllegalTokens(@TempDir Path tempDir) {
        ProcessSandboxGuard guard = new ProcessSandboxGuard(tempDir);

        assertThatThrownBy(() -> guard.validateCommandTokens(List.of("git", "status; rm -rf /")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("contains illegal shell control character");
    }

    @Test
    @DisplayName("Sanitizes sensitive environment variables")
    void sanitizesEnvironmentVariables(@TempDir Path tempDir) {
        ProcessSandboxGuard guard = new ProcessSandboxGuard(tempDir);
        Map<String, String> originalEnv = Map.of(
                "PATH", "/usr/bin",
                "OPENAI_API_KEY", "sk-proj-secret123",
                "AWS_SECRET_ACCESS_KEY", "super-secret",
                "UNEXPECTED_VENDOR_CREDENTIAL", "also-secret"
        );

        Map<String, String> sanitized = guard.sanitizeEnvironment(originalEnv);

        assertThat(sanitized).containsEntry("PATH", "/usr/bin");
        assertThat(sanitized).doesNotContainKey("OPENAI_API_KEY");
        assertThat(sanitized).doesNotContainKey("AWS_SECRET_ACCESS_KEY");
        assertThat(sanitized).doesNotContainKey("UNEXPECTED_VENDOR_CREDENTIAL");
    }

    @Test
    void appliesSanitizedEnvironmentToMutableProcessMap(@TempDir Path tempDir) {
        ProcessSandboxGuard guard = new ProcessSandboxGuard(tempDir);
        Map<String, String> environment = new HashMap<>(Map.of(
                "PATH", "/usr/bin",
                "OPENAI_API_KEY", "secret",
                "CUSTOM_SECRET", "secret-two"));

        guard.applySanitizedEnvironment(environment);

        assertThat(environment).containsOnlyKeys("PATH");
    }
}
