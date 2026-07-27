package dev.justnels.castcli.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceToolsTest {
    @TempDir
    Path root;

    @Test
    void readsAndSearchesInsideWorkspace() throws Exception {
        Files.writeString(root.resolve("Example.java"), "class Example { // needle\n}");
        WorkspaceTools tools = new WorkspaceTools(root, 1024);

        assertThat(tools.readWorkspaceFile("Example.java")).contains("needle");
        assertThat(tools.searchWorkspace("needle", 10)).containsExactly("Example.java:1:class Example { // needle");
    }

    @Test
    void rejectsPathTraversal() {
        WorkspaceTools tools = new WorkspaceTools(root, 1024);
        assertThatThrownBy(() -> tools.readWorkspaceFile("../secret.txt"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void writeDeniedWhenAllowWritesIsFalse() throws Exception {
        WorkspaceTools tools = new WorkspaceTools(root, 1024, false, AutoApprovalGate.INSTANCE);
        String result = tools.writeWorkspaceFile("New.java", "class New {}");
        assertThat(result).contains("Write denied");
        assertThat(root.resolve("New.java")).doesNotExist();
    }

    @Test
    void writeDeniedByApprovalGateEvenWhenAllowed() throws Exception {
        WorkspaceTools tools = new WorkspaceTools(root, 1024, true, (action, detail) -> false);
        String result = tools.writeWorkspaceFile("New.java", "class New {}");
        assertThat(result).contains("denied by approval gate");
        assertThat(root.resolve("New.java")).doesNotExist();
    }

    @Test
    void writesFileWhenAllowedAndApproved() throws Exception {
        WorkspaceTools tools = new WorkspaceTools(root, 1024, true, AutoApprovalGate.INSTANCE);
        String result = tools.writeWorkspaceFile("sub/New.java", "class New {}");
        assertThat(result).contains("Created");
        assertThat(Files.readString(root.resolve("sub/New.java"))).isEqualTo("class New {}");
    }
}

