package dev.justnels.castcli.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceToolsSecurityTest {
    @TempDir
    Path tempDir;

    private Path workspaceRoot;
    private WorkspaceTools tools;

    @BeforeEach
    void setUp() throws IOException {
        workspaceRoot = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspaceRoot);
        tools = new WorkspaceTools(workspaceRoot, 1024 * 1024, true, AutoApprovalGate.INSTANCE);
    }

    @Test
    void allowsValidRelativePathReadAndWrite() throws IOException {
        String result = tools.writeWorkspaceFile("test.txt", "hello security");
        assertThat(result).contains("Created test.txt");

        String content = tools.readWorkspaceFile("test.txt");
        assertThat(content).isEqualTo("hello security");
    }

    @Test
    void rejectsParentTraversalPath() {
        assertThatThrownBy(() -> tools.readWorkspaceFile("../secret.txt"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Path escapes workspace");
    }

    @Test
    void rejectsAbsolutePaths() {
        Path outsideFile = tempDir.resolve("outside.txt");
        assertThatThrownBy(() -> tools.readWorkspaceFile(outsideFile.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Absolute paths are not allowed");
    }

    @Test
    void rejectsNullOrBlankPath() {
        assertThatThrownBy(() -> tools.readWorkspaceFile(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.readWorkspaceFile("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.readWorkspaceFile(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSymlinkEscapingWorkspace() throws IOException {
        Path outsideDir = tempDir.resolve("outside_dir");
        Files.createDirectories(outsideDir);
        Path secretFile = outsideDir.resolve("secret.txt");
        Files.writeString(secretFile, "top secret");

        Path symlinkInWorkspace = workspaceRoot.resolve("symlink_out");
        try {
            Files.createSymbolicLink(symlinkInWorkspace, outsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            // Skip symlink test if OS / filesystem privilege doesn't support symlink creation
            return;
        }

        assertThatThrownBy(() -> tools.readWorkspaceFile("symlink_out/secret.txt"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("escapes workspace via symlink");
        assertThat(tools.listWorkspaceFiles("**/*", 100))
                .doesNotContain(Path.of("symlink_out", "secret.txt").toString());
        assertThat(tools.searchWorkspace("top secret", 100)).isEmpty();
    }

    @Test
    void deniesAndDoesNotEnumerateSensitiveWorkspaceState() throws IOException {
        Files.writeString(workspaceRoot.resolve(".env"), "OPENAI_API_KEY=secret");
        Files.writeString(workspaceRoot.resolve("PROD-SECRET.TXT"), "uppercase secret");
        Files.createDirectories(workspaceRoot.resolve(".git"));
        Files.writeString(workspaceRoot.resolve(".git/config"), "credential=secret");
        Files.createDirectories(workspaceRoot.resolve(".cast"));
        Files.writeString(workspaceRoot.resolve(".cast/audit.jsonl"), "sensitive context");
        Files.writeString(workspaceRoot.resolve("visible.txt"), "safe");

        assertThatThrownBy(() -> tools.readWorkspaceFile(".env"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("sensitive workspace path");
        assertThat(tools.listWorkspaceFiles("**/*", 100))
                .contains("visible.txt")
                .doesNotContain(".env", Path.of(".git", "config").toString(),
                        Path.of(".cast", "audit.jsonl").toString());
        assertThat(tools.searchWorkspace("secret", 100)).isEmpty();
        assertThatThrownBy(() -> tools.readWorkspaceFile("PROD-SECRET.TXT"))
                .isInstanceOf(SecurityException.class);
    }
}
