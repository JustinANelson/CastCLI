package dev.justnels.castcli.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.orchestration.ModelRouter;
import dev.justnels.castcli.orchestration.PolicyRoutingStrategy;
import dev.justnels.castcli.orchestration.RoutingCandidate;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.reliability.ProviderHealthRegistry;
import dev.justnels.castcli.tools.AutoApprovalGate;
import dev.justnels.castcli.tools.DefaultToolSelector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Deterministic, no-model-call benchmark for routing and tool selection policy. */
public final class RoutingEvaluator {
    private final ObjectMapper mapper = new ObjectMapper();

    public RoutingEvaluationDataset load(Path path) throws Exception {
        return mapper.readValue(path.toFile(), RoutingEvaluationDataset.class);
    }

    public RoutingEvaluationReport evaluate(HarnessConfig config, RoutingEvaluationDataset dataset) {
        ModelRouter router = new ModelRouter(config, new PolicyRoutingStrategy(), new ProviderHealthRegistry(config.reliability()));
        DefaultToolSelector selector = new DefaultToolSelector();
        List<RoutingEvaluationReport.Decision> decisions = new ArrayList<>();
        int passed = 0, providerMatches = 0, tierMatches = 0, providerExpected = 0, tierExpected = 0, privacyViolations = 0, cloudRoutes = 0;
        double totalRate = 0;
        for (RoutingEvaluationCase testCase : dataset.cases()) {
            TaskRequest task = new TaskRequest(testCase.prompt(), testCase.workload(), testCase.requestedTier());
            List<Object> tools = selector.selectTools(task, config.tools(), AutoApprovalGate.INSTANCE);
            List<String> toolNames = tools.stream().map(tool -> tool.getClass().getSimpleName()).toList();
            List<RoutingCandidate> ranked = router.rank(task, tools);
            if (ranked.isEmpty()) {
                decisions.add(new RoutingEvaluationReport.Decision(testCase.id(), null, null, 0, toolNames, List.of(), List.of("no candidate")));
                continue;
            }
            RoutingCandidate selected = ranked.getFirst();
            List<String> failures = new ArrayList<>();
            if (testCase.expectedProviderId() != null) {
                providerExpected++;
                if (testCase.expectedProviderId().equals(selected.provider().id())) providerMatches++; else failures.add("provider");
            }
            if (testCase.expectedTier() != null) {
                tierExpected++;
                if (testCase.expectedTier() == selected.provider().tier()) tierMatches++; else failures.add("tier");
            }
            if (!testCase.cloudAllowed() && selected.provider().tier() == ModelTier.FRONTIER_CLOUD) {
                privacyViolations++; failures.add("cloud-policy");
            }
            for (String required : testCase.requiredTools()) if (!toolNames.contains(required)) failures.add("missing-tool:" + required);
            if (failures.isEmpty()) passed++;
            if (selected.provider().tier() == ModelTier.FRONTIER_CLOUD) cloudRoutes++;
            totalRate += selected.provider().costPerMillionInputTokens() + selected.provider().costPerMillionOutputTokens();
            decisions.add(new RoutingEvaluationReport.Decision(testCase.id(), selected.provider().id(), selected.provider().tier(),
                    selected.score(), toolNames, selected.reasons(), List.copyOf(failures)));
        }
        int total = dataset.cases().size();
        return new RoutingEvaluationReport(dataset.name(), total, passed,
                providerExpected == 0 ? 1 : providerMatches / (double) providerExpected,
                tierExpected == 0 ? 1 : tierMatches / (double) tierExpected,
                privacyViolations, cloudRoutes / (double) total, totalRate / total, List.copyOf(decisions));
    }
}
