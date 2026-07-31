package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReworkVerifierTest {

    private HarnessConfig mockConfig() {
        ProviderConfig localProvider = new ProviderConfig(
                "local-test", ModelTier.SMALL_LOCAL, "http://fake/v1/", "model", null, 0.0, 10, true, true);
        return new HarnessConfig(List.of(localProvider), null, null);
    }

    @Test
    void retriesUntilVerificationPasses() {
        AtomicInteger attempts = new AtomicInteger(0);
        HarnessOrchestrator fakeOrchestrator = new HarnessOrchestrator(mockConfig()) {
            @Override
            public Outcome run(TaskRequest task) {
                int count = attempts.incrementAndGet();
                if (count == 1) {
                    return new Outcome(mockConfig().providers().get(0), "INVALID CODE SYNTX", List.of());
                }
                return new Outcome(mockConfig().providers().get(0), "public class Valid {}", List.of());
            }
        };

        LocalReworkVerifier verifier = new LocalReworkVerifier(fakeOrchestrator, 3);
        TaskRequest task = new TaskRequest("Write a Java class", Workload.CODE, ModelTier.SMALL_LOCAL);

        HarnessOrchestrator.Outcome outcome = verifier.runWithPredicate(task, answer ->
                answer != null && answer.contains("public class"));

        assertThat(outcome).isNotNull();
        assertThat(outcome.answer()).contains("public class Valid");
        assertThat(attempts.get()).isEqualTo(2);
    }
}
