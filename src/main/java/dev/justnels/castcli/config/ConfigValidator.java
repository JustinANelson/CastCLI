package dev.justnels.castcli.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates CastCLI configuration files for production runtime safety, checking provider keys,
 * workspace paths, memory storage access, and observability settings.
 */
public final class ConfigValidator {

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public ValidationResult validate(HarnessConfig config, Path configPath) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Config File Path Check
        if (configPath != null) {
            if (!Files.exists(configPath)) {
                warnings.add("Configuration file does not exist at specified path: " + configPath);
            } else if (!Files.isReadable(configPath)) {
                errors.add("Configuration file is not readable: " + configPath);
            }
        }

        if (config == null) {
            errors.add("Configuration object is null");
            return new ValidationResult(false, errors, warnings);
        }

        // 2. Workspace Root Check
        if (config.tools() != null && config.tools().workspaceRoot() != null) {
            Path workspace = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
            if (!Files.exists(workspace)) {
                errors.add("Workspace root directory does not exist: " + workspace);
            } else if (!Files.isDirectory(workspace)) {
                errors.add("Workspace root is not a directory: " + workspace);
            } else if (!Files.isWritable(workspace)) {
                warnings.add("Workspace root directory is read-only: " + workspace);
            }
        } else {
            errors.add("Workspace root directory is not configured in tools settings");
        }

        // 3. Provider Configuration Check
        if (config.providers() == null || config.providers().isEmpty()) {
            errors.add("No model providers configured");
        } else {
            long enabledCount = config.providers().stream().filter(ProviderConfig::enabled).count();
            if (enabledCount == 0) {
                errors.add("At least one provider must be enabled");
            }

            for (ProviderConfig provider : config.providers()) {
                if (!provider.enabled()) continue;

                if (provider.modelName() == null || provider.modelName().isBlank()) {
                    errors.add("Provider '" + provider.id() + "' has an empty or missing modelName");
                }

                if (provider.apiKeyEnv() != null && !provider.apiKeyEnv().isBlank()) {
                    String envVal = System.getenv(provider.apiKeyEnv());
                    if (envVal == null || envVal.isBlank()) {
                        warnings.add("Provider '" + provider.id() + "' requires environment variable '"
                                + provider.apiKeyEnv() + "' which is not currently set");
                    }
                }
            }
        }

        // 4. Memory Store Check
        if (config.memory() != null && config.memory().enabled()) {
            if (config.memory().databasePath() == null || config.memory().databasePath().isBlank()) {
                errors.add("Memory is enabled but databasePath is empty");
            } else {
                Path dbPath = Path.of(config.memory().databasePath());
                Path parent = dbPath.isAbsolute() ? dbPath.getParent() : dbPath.toAbsolutePath().normalize().getParent();
                if (parent != null && Files.exists(parent) && !Files.isWritable(parent)) {
                    errors.add("Memory database parent directory is not writable: " + parent);
                }
            }
        }

        // 5. Observability Check
        if (config.observability() != null && config.observability().enabled()) {
            if (config.observability().otlpEnabled()) {
                String endpoint = config.observability().otlpEndpoint();
                if (endpoint == null || endpoint.isBlank()) {
                    errors.add("Observability OTLP is enabled but otlpEndpoint is empty");
                } else {
                    try {
                        URI uri = URI.create(endpoint);
                        if (uri.getScheme() == null || uri.getHost() == null) {
                            warnings.add("Observability OTLP endpoint may be malformed: " + endpoint);
                        }
                    } catch (Exception e) {
                        errors.add("Invalid OTLP endpoint URI: " + endpoint);
                    }
                }
            }
        }

        // 6. Tool Settings Check
        if (config.tools() != null) {
            if (config.tools().maxFileBytes() <= 0) {
                errors.add("Tool maxFileBytes must be greater than zero");
            }
        }

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings);
    }
}
