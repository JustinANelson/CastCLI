package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.ProviderConfig;
import java.util.List;

public record RoutingCandidate(ProviderConfig provider, double score, List<String> reasons) {
    public RoutingCandidate { reasons = List.copyOf(reasons); }
}
