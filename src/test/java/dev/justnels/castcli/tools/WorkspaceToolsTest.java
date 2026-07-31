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
    void boundsIndividualSearchResultLines() throws Exception {
        Files.writeString(root.resolve("Large.java"), "needle " + "x".repeat(2_000));
        WorkspaceTools tools = new WorkspaceTools(root, 4_096);

        String match = tools.searchWorkspace("needle", 10).getFirst();

        assertThat(match).endsWith("...[truncated]");
        assertThat(match.length()).isLessThan(450);
    }

    @Test
    void boundsFileRetrievalBeforePromptAssembly() throws Exception {
        Files.writeString(root.resolve("Large.java"), "x".repeat(2_000));
        WorkspaceTools tools = new WorkspaceTools(root, 4_096);

        String content = tools.readWorkspaceFile("Large.java", 100);

        assertThat(content).hasSize(100).endsWith("[file content omitted by retrieval budget]");
    }

    @Test
    void excludesGeneratedTreesFromListingAndSearch() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("build/generated"));
        Files.writeString(root.resolve("src/Source.java"), "needle");
        Files.writeString(root.resolve("build/generated/Generated.java"), "needle");
        WorkspaceTools tools = new WorkspaceTools(root, 1_024);

        assertThat(tools.searchWorkspace("needle", 10))
                .containsExactly(Path.of("src", "Source.java") + ":1:needle");
        assertThat(tools.listWorkspaceFiles("**/*.java", 10))
                .containsExactly(Path.of("src", "Source.java").toString());
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

