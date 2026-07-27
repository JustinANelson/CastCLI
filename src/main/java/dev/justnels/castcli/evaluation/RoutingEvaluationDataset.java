package dev.justnels.castcli.evaluation;

import java.util.List;

public record RoutingEvaluationDataset(String name, List<RoutingEvaluationCase> cases) {
    public RoutingEvaluationDataset {
        name = name == null || name.isBlank() ? "unnamed" : name;
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (cases.isEmpty()) throw new IllegalArgumentException("evaluation dataset must contain at least one case");
    }
}
