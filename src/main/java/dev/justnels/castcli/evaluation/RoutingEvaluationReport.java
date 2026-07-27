package dev.justnels.castcli.evaluation;

import dev.justnels.castcli.config.ModelTier;
import java.util.List;

public record RoutingEvaluationReport(
        String dataset,
        int total,
        int passed,
        double providerAccuracy,
        double tierAccuracy,
        int privacyViolations,
        double cloudRouteShare,
        double averageCombinedRatePerMillionTokens,
        List<Decision> decisions) {

    public record Decision(String caseId, String providerId, ModelTier tier, double score,
                           List<String> tools, List<String> reasons, List<String> failures) { }
}
