package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.ReliabilityConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderHealthRegistryTest {

    @Test
    void testStatePersistence(@TempDir Path tempDir) throws IOException {
        ReliabilityConfig config = new ReliabilityConfig(3, 60, 3000, 3, 60, 300, 16, Map.of());
        ProviderHealthRegistry registry1 = new ProviderHealthRegistry(config);

        ProviderConfig provider = new ProviderConfig("test-provider", ModelTier.SMALL_LOCAL,
                "http://localhost:11434/v1/", "qwen3.5:9b", null, 0.1, 120, true, true, 4, 0.0, 0.0);

        registry1.recordFailure(provider, FailureKind.TIMEOUT);
        registry1.recordFailure(provider, FailureKind.TIMEOUT);
        registry1.recordFailure(provider, FailureKind.TIMEOUT);

        assertFalse(registry1.isAvailable(provider), "Circuit should be open after 3 failures");

        Path stateFile = tempDir.resolve("health-state.json");
        registry1.saveState(stateFile);

        assertTrue(java.nio.file.Files.exists(stateFile), "State file should exist");

        ProviderHealthRegistry registry2 = new ProviderHealthRegistry(config);
        registry2.loadState(stateFile);

        assertFalse(registry2.isAvailable(provider), "Loaded registry should reflect open circuit");
        assertEquals(3, registry2.consecutiveFailures(provider), "Consecutive failure count should match");
    }
}
