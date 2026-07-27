package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.ModelTier;

public record TaskRequest(String prompt, Workload workload, ModelTier requestedTier, boolean strict) {
    public TaskRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        dev.justnels.castcli.security.PromptSanitizer.validate(prompt);
        if (workload == null) {
            workload = Workload.AUTO;
        }
        if (strict && requestedTier == null) {
            throw new IllegalArgumentException("strict requires an explicit requestedTier");
        }
    }

    public TaskRequest(String prompt, Workload workload, ModelTier requestedTier) {
        this(prompt, workload, requestedTier, false);
    }
}

