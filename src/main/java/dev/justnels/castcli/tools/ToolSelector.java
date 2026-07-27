package dev.justnels.castcli.tools;

import dev.justnels.castcli.config.ToolConfig;
import dev.justnels.castcli.orchestration.TaskRequest;

import java.util.List;

public interface ToolSelector {
    default List<Object> selectTools(TaskRequest task, ToolConfig config) {
        return selectTools(task, config, AutoApprovalGate.INSTANCE);
    }

    List<Object> selectTools(TaskRequest task, ToolConfig config, ApprovalGate approvalGate);
}

