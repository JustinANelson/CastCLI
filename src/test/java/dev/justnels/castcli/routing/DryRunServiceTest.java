package dev.justnels.castcli.routing;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;

import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DryRunServiceTest {

    @Test
    void evaluatesDryRunReportForLocalTask() {
        ProviderConfig localProvider = new ProviderConfig(
                "local-test", ModelTier.SMALL_LOCAL, "http://localhost:11434/v1/",
                "qwen2.5-coder", null, 0.7, 30, true, true, 8, 0.0, 0.0);

        HarnessConfig config = new HarnessConfig(List.of(localProvider),
                new dev.justnels.castcli.config.RoutingConfig(30, true),
                new dev.justnels.castcli.config.ToolConfig(".", 1000, false));

        TaskRequest request = new TaskRequest("what time is it", Workload.QUICK, ModelTier.SMALL_LOCAL, false);
        DryRunService service = new DryRunService();
        DryRunService.DryRunReport report = service.dryRun(config, request);

        assertNotNull(report);
        assertNotNull(report.selectedProvider());
        assertTrue(report.candidateRankings().stream().anyMatch(c -> c.id().equals("local-test")));
        assertFalse(report.cloudEgressExpected());
        assertTrue(report.toHumanReadableString().contains("CastCLI Dry-Run Routing Report"));
    }

    @Test
    void listsExcludedProvidersWithReasons() {
        ProviderConfig disabledProvider = new ProviderConfig(
                "disabled-prov", ModelTier.FRONTIER_CLOUD, "https://api.openai.com/v1/",
                "gpt-4o", "OPENAI_API_KEY", 0.7, 30, true, false, 8, 2.5, 10.0);

        HarnessConfig config = new HarnessConfig(List.of(disabledProvider),
                new dev.justnels.castcli.config.RoutingConfig(30, true),
                new dev.justnels.castcli.config.ToolConfig(".", 1000, false));

        TaskRequest request = new TaskRequest("write code", Workload.CODE, null, false);
        DryRunService service = new DryRunService();
        DryRunService.DryRunReport report = service.dryRun(config, request);

        assertNotNull(report);
        assertTrue(report.excludedProviders().stream().anyMatch(e -> e.id().equals("disabled-prov")));
    }
}
