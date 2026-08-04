package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouterTest {
    private final HarnessConfig config = new HarnessConfig(List.of(
            provider("small", ModelTier.SMALL_LOCAL),
            provider("large", ModelTier.LARGE_LOCAL),
            provider("cloud", ModelTier.FRONTIER_CLOUD)),
            new RoutingConfig(30, true), new ToolConfig(".", 1000, false));

    @Test
    void routesQuickWorkToSmallLocal() {
        ProviderConfig selected = new ModelRouter(config)
                .route(new TaskRequest("Summarize this", Workload.QUICK, null));
        assertThat(selected.id()).isEqualTo("small");
    }

    @Test
    void routesCodeWorkToLargeLocal() {
        ProviderConfig selected = new ModelRouter(config)
                .route(new TaskRequest("Debug this Java code", Workload.AUTO, null));
        assertThat(selected.id()).isEqualTo("large");
    }

    @Test
    void explicitTierOverridesClassification() {
        ProviderConfig selected = new ModelRouter(config)
                .route(new TaskRequest("hello", Workload.AUTO, ModelTier.FRONTIER_CLOUD));
        assertThat(selected.id()).isEqualTo("cloud");
    }

    @Test
    void routesToLargeLocalWhenComplexToolPresent() {
        ProviderConfig selected = new ModelRouter(config)
                .route(new TaskRequest("eval math", Workload.QUICK, null),
                        List.of(new dev.justnels.castcli.tools.JavaShellTool(true)));
        assertThat(selected.id()).isEqualTo("large");
    }

    @Test
    void strictRoutingRejectsNearestTierSubstitution() {
        HarnessConfig cloudless = new HarnessConfig(List.of(
                provider("small", ModelTier.SMALL_LOCAL),
                provider("large", ModelTier.LARGE_LOCAL)),
                new RoutingConfig(30, true), new ToolConfig(".", 1000, false));

        assertThatThrownBy(() -> new ModelRouter(cloudless)
                .route(new TaskRequest("plan this", Workload.REASONING, ModelTier.FRONTIER_CLOUD, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FRONTIER_CLOUD");
    }

    @Test
    void strictRoutingSucceedsWhenExactTierAvailable() {
        ProviderConfig selected = new ModelRouter(config)
                .route(new TaskRequest("plan this", Workload.REASONING, ModelTier.FRONTIER_CLOUD, true));
        assertThat(selected.id()).isEqualTo("cloud");
    }

    @Test
    void exactProviderSelectionDisambiguatesProvidersInTheSameTier() {
        ProviderConfig selected = new ModelRouter(config)
                .route(new TaskRequest("implement this", Workload.CODE, null, "small", true, false));

        assertThat(selected.id()).isEqualTo("small");
    }

    @Test
    void exactProviderSelectionDoesNotSilentlySubstitute() {
        assertThatThrownBy(() -> new ModelRouter(config)
                .route(new TaskRequest("plan this", Workload.REASONING, null, "missing", true, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider 'missing'");
    }

    @Test
    void privacyPolicyExcludesCloudAndDecisionIsExplainable() {
        ModelRouter router = new ModelRouter(config);
        TaskRequest task = new TaskRequest("Analyze confidential customer data; do not upload it", Workload.REASONING, null);
        List<RoutingCandidate> ranked = router.rank(task, List.of());
        assertThat(ranked).isNotEmpty().allMatch(candidate -> candidate.provider().tier() != ModelTier.FRONTIER_CLOUD);
        assertThat(ranked.getFirst().reasons()).contains("privacy-locality");
    }

    private static ProviderConfig provider(String id, ModelTier tier) {
        return new ProviderConfig(id, tier, "http://localhost/v1/", id, null,
                0, 30, true, true);
    }
}


