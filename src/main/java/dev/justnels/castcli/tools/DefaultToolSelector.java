package dev.justnels.castcli.tools;

import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DefaultToolSelector implements ToolSelector {
    private static final List<String> SYSTEM_MARKERS = List.of(
            "time", "date", "clock", "now", "today", "year", "timezone", "zone");

    private static final List<String> WORKSPACE_MARKERS = List.of(
            "file", "workspace", "repo", "repository", "search", "find",
            "directory", "folder", "list", "read", "grep", "line", "class", "method",
            "write", "create", "save", "edit", "modify");

    private static final List<String> SHELL_MARKERS = List.of(
            "eval", "jshell", "evaluate", "java expression", "calculate", "math", "math.pow", "math.sqrt", "formula");

    private static final List<String> PROCESS_MARKERS = List.of(
            "test", "gradle", "build", "compile", "run the tests", "git status", "git diff", "git log");

    @Override
    public List<Object> selectTools(TaskRequest task, ToolConfig config, ApprovalGate approvalGate) {
        List<Object> tools = new ArrayList<>();
        String normalized = task.prompt().toLowerCase(Locale.ROOT);
        ApprovalGate gate = approvalGate == null ? AutoApprovalGate.INSTANCE : approvalGate;

        boolean needsSystem = containsAny(normalized, SYSTEM_MARKERS);
        boolean needsWorkspace = containsAny(normalized, WORKSPACE_MARKERS) || task.workload() == Workload.CODE;
        boolean needsShell = config.jshellEnabled() && containsAny(normalized, SHELL_MARKERS);
        boolean needsProcess = config.allowShellExec() && containsAny(normalized, PROCESS_MARKERS);

        if (needsSystem) {
            tools.add(new SystemTools());
        }
        if (needsWorkspace) {
            tools.add(new WorkspaceTools(Path.of(config.workspaceRoot()), config.maxFileBytes(), config.allowWrites(), gate));
        }
        if (needsShell) {
            tools.add(new JavaShellTool(config.jshellEnabled()));
        }
        if (needsProcess) {
            tools.add(new ProcessExecTool(Path.of(config.workspaceRoot()), config.allowShellExec(), gate));
        }

        return tools;
    }

    private static boolean containsAny(String input, List<String> markers) {
        return markers.stream().anyMatch(input::contains);
    }
}

