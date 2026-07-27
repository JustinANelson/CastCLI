package dev.justnels.castcli.evaluation;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingEvaluatorTest {
    @Test
    void evaluatesVersionedDatasetWithoutModelCalls() throws Exception {
        HarnessConfig config = new HarnessConfig(List.of(
                provider("small", ModelTier.SMALL_LOCAL), provider("large", ModelTier.LARGE_LOCAL),
                provider("cloud", ModelTier.FRONTIER_CLOUD)),
                new RoutingConfig(240, true), new ToolConfig(".", 100_000, false));
        RoutingEvaluator evaluator = new RoutingEvaluator();
        RoutingEvaluationReport report = evaluator.evaluate(config, evaluator.load(Path.of("evals/routing.example.json")));

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.passed()).isEqualTo(3);
        assertThat(report.privacyViolations()).isZero();
        assertThat(report.decisions()).allMatch(decision -> decision.reasons() != null && !decision.reasons().isEmpty());
    }

    private static ProviderConfig provider(String id, ModelTier tier) {
        return new ProviderConfig(id, tier, "http://localhost/v1/", id, null, 0, 30, true, true, 10, 0, 0);
    }
}
