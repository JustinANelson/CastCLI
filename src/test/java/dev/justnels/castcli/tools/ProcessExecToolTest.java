package dev.justnels.castcli.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessExecToolTest {
    @TempDir
    Path workDir;

    @Test
    void deniedWhenExecNotAllowed() throws Exception {
        ProcessExecTool tool = new ProcessExecTool(workDir, false, AutoApprovalGate.INSTANCE);
        assertThat(tool.runCommand("git-status")).contains("Execution denied");
    }

    @Test
    void deniedByApprovalGate() throws Exception {
        ProcessExecTool tool = new ProcessExecTool(workDir, true, (action, detail) -> false);
        assertThat(tool.runCommand("git-status")).contains("denied by approval gate");
    }

    @Test
    void rejectsUnknownCommandKey() throws Exception {
        ProcessExecTool tool = new ProcessExecTool(workDir, true, AutoApprovalGate.INSTANCE);
        assertThat(tool.runCommand("rm -rf /")).contains("Unknown commandKey");
    }

    @Test
    void runsAllowListedGitStatus() throws Exception {
        ProcessBuilder init = new ProcessBuilder("git", "init", "-q").directory(workDir.toFile());
        init.start().waitFor();

        ProcessExecTool tool = new ProcessExecTool(workDir, true, AutoApprovalGate.INSTANCE);
        String result = tool.runCommand("git-status");
        assertThat(result).contains("Exit code: 0");
    }
}

