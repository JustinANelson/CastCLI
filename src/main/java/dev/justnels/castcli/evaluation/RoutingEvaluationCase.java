package dev.justnels.castcli.evaluation;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.orchestration.Workload;
import java.util.List;

public record RoutingEvaluationCase(
        String id,
        String prompt,
        Workload workload,
        ModelTier requestedTier,
        String expectedProviderId,
        ModelTier expectedTier,
        Boolean cloudAllowed,
        List<String> requiredTools) {
    public RoutingEvaluationCase {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("evaluation case id must not be blank");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("evaluation prompt must not be blank");
        workload = workload == null ? Workload.AUTO : workload;
        cloudAllowed = cloudAllowed == null ? Boolean.TRUE : cloudAllowed;
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
    }
}
