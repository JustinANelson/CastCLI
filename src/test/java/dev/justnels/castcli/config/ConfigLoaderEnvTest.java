package dev.justnels.castcli.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoaderEnvTest {

    @Test
    void expandsExistingEnvironmentVariable() {
        Map<String, String> env = Map.of("CUSTOM_HOST", "https://api.custom.com", "API_KEY", "secret-key-123");
        String template = "{\"baseUrl\": \"${CUSTOM_HOST}\", \"key\": \"${API_KEY}\"}";

        String expanded = ConfigLoader.expandEnvironmentVariables(template, env);
        assertThat(expanded).isEqualTo("{\"baseUrl\": \"https://api.custom.com\", \"key\": \"secret-key-123\"}");
    }

    @Test
    void usesDefaultValueWhenVariableMissing() {
        Map<String, String> env = Map.of();
        String template = "{\"baseUrl\": \"${API_HOST:https://localhost:11434}\", \"timeout\": \"${TIMEOUT:30}\"}";

        String expanded = ConfigLoader.expandEnvironmentVariables(template, env);
        assertThat(expanded).isEqualTo("{\"baseUrl\": \"https://localhost:11434\", \"timeout\": \"30\"}");
    }

    @Test
    void prefersEnvironmentValueOverDefaultValue() {
        Map<String, String> env = Map.of("API_HOST", "https://prod.remote:8080");
        String template = "{\"baseUrl\": \"${API_HOST:https://localhost:11434}\"}";

        String expanded = ConfigLoader.expandEnvironmentVariables(template, env);
        assertThat(expanded).isEqualTo("{\"baseUrl\": \"https://prod.remote:8080\"}");
    }

    @Test
    void leavesUnmatchedPlaceholderIntactIfNoDefault() {
        Map<String, String> env = Map.of();
        String template = "{\"name\": \"${UNDEFINED_VAR}\"}";

        String expanded = ConfigLoader.expandEnvironmentVariables(template, env);
        assertThat(expanded).isEqualTo("{\"name\": \"${UNDEFINED_VAR}\"}");
    }
}
