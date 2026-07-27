package dev.justnels.castcli.config;

public record ToolConfig(
        String workspaceRoot,
        long maxFileBytes,
        boolean jshellEnabled,
        boolean allowWrites,
        boolean allowShellExec,
        boolean requireApproval) {
    public ToolConfig {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            workspaceRoot = ".";
        }
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
    }

    public ToolConfig(String workspaceRoot, long maxFileBytes, boolean jshellEnabled) {
        this(workspaceRoot, maxFileBytes, jshellEnabled, false, false, true);
    }
}

