package dev.justnels.castcli.validation;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.Workload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionCascadeEngineTest {

    @Test
    void passesImmediatelyWhenValidationSucceeds() {
        ProviderConfig localProvider = new ProviderConfig(
                "local-test", ModelTier.SMALL_LOCAL, "http://localhost:11434/v1/",
                "qwen2.5-coder", null, 0.7, 30, true, true, 8, 0.0, 0.0);
        HarnessConfig config = new HarnessConfig(List.of(localProvider),
                new dev.justnels.castcli.config.RoutingConfig(30, true),
                new dev.justnels.castcli.config.ToolConfig(".", 1000, false));

        TaskRequest request = new TaskRequest("generate config", Workload.QUICK, ModelTier.SMALL_LOCAL, false);
        JsonSchemaValidator validator = new JsonSchemaValidator(List.of("status"));

        ExecutionCascadeEngine engine = new ExecutionCascadeEngine();
        ExecutionCascadeEngine.CascadeResult result = engine.execute(
                config, request, List.of(validator),
                req -> new HarnessOrchestrator.Outcome(localProvider, "{\"status\": \"ok\"}", List.of()));

        assertTrue(result.success());
        assertEquals(1, result.localAttempts());
        assertFalse(result.escalatedToCloud());
    }

    @Test
    void retriesLocallyWhenValidationFailsAndSucceedsOnSecondAttempt() {
        ProviderConfig localProvider = new ProviderConfig(
                "local-test", ModelTier.SMALL_LOCAL, "http://localhost:11434/v1/",
                "qwen2.5-coder", null, 0.7, 30, true, true, 8, 0.0, 0.0);
        HarnessConfig config = new HarnessConfig(List.of(localProvider),
                new dev.justnels.castcli.config.RoutingConfig(30, true),
                new dev.justnels.castcli.config.ToolConfig(".", 1000, false));

        TaskRequest request = new TaskRequest("generate config", Workload.QUICK, ModelTier.SMALL_LOCAL, false);
        JsonSchemaValidator validator = new JsonSchemaValidator(List.of("status"));

        AtomicInteger callCount = new AtomicInteger(0);

        ExecutionCascadeEngine engine = new ExecutionCascadeEngine();
        ExecutionCascadeEngine.CascadeResult result = engine.execute(
                config, request, List.of(validator),
                req -> {
                    int count = callCount.incrementAndGet();
                    if (count == 1) {
                        return new HarnessOrchestrator.Outcome(localProvider, "invalid response", List.of());
                    }
                    return new HarnessOrchestrator.Outcome(localProvider, "{\"status\": \"fixed\"}", List.of());
                });

        assertTrue(result.success());
        assertEquals(2, result.localAttempts());
        assertFalse(result.escalatedToCloud());
    }
}
