package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.reliability.ProviderHealthRegistry;
import java.util.List;

@FunctionalInterface
public interface RoutingStrategy {
    List<RoutingCandidate> rank(TaskRequest task, List<Object> selectedTools, HarnessConfig config,
                                List<ProviderConfig> eligible, ProviderHealthRegistry health);
}
