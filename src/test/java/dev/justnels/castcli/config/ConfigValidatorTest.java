package dev.justnels.castcli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidatorTest {

    @Test
    void validateValidConfigReturnsSuccess(@TempDir Path tempDir) {
        ProviderConfig provider = new ProviderConfig("openai", ModelTier.FRONTIER_CLOUD, "https://api.openai.com/v1", "gpt-4o", "OPENAI_API_KEY", 0.7, 30, true, true);
        ToolConfig tools = new ToolConfig(tempDir.toString(), 262_144, false);
        HarnessConfig config = new HarnessConfig(List.of(provider), new RoutingConfig(240, true), tools);

        ConfigValidator validator = new ConfigValidator();
        ConfigValidator.ValidationResult result = validator.validate(config, tempDir.resolve("harness.json"));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateMissingWorkspaceRootFlagsError(@TempDir Path tempDir) {
        ProviderConfig provider = new ProviderConfig("openai", ModelTier.FRONTIER_CLOUD, "https://api.openai.com/v1", "gpt-4o", "OPENAI_API_KEY", 0.7, 30, true, true);
        ToolConfig tools = new ToolConfig(tempDir.resolve("non-existent-dir").toString(), 262_144, false);
        HarnessConfig config = new HarnessConfig(List.of(provider), new RoutingConfig(240, true), tools);

        ConfigValidator validator = new ConfigValidator();
        ConfigValidator.ValidationResult result = validator.validate(config, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(err -> err.contains("Workspace root directory does not exist"));
    }

    @Test
    void validateInvalidOtlpEndpointFlagsError(@TempDir Path tempDir) {
        ProviderConfig provider = new ProviderConfig("openai", ModelTier.FRONTIER_CLOUD, "https://api.openai.com/v1", "gpt-4o", "OPENAI_API_KEY", 0.7, 30, true, true);
        ToolConfig tools = new ToolConfig(tempDir.toString(), 262_144, false);
        ObservabilityConfig obs = new ObservabilityConfig(true, "test-service", true, "invalid uri format with spaces", ".cast/traces.jsonl", 10, 1.0, false, true, java.util.Map.of(), java.util.Map.of());
        HarnessConfig config = new HarnessConfig(List.of(provider), new RoutingConfig(240, true), tools, List.of(), null, null, null, obs);

        ConfigValidator validator = new ConfigValidator();
        ConfigValidator.ValidationResult result = validator.validate(config, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(err -> err.contains("Invalid OTLP endpoint URI"));
    }
}
