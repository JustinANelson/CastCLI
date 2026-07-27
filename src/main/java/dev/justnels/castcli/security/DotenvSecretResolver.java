package dev.justnels.castcli.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves secrets from a local {@code .env} file (simple {@code KEY=VALUE} lines), so solo/indie
 * setups testing multiple cloud providers don't have to hand-export environment variables. Real
 * process environment variables always take priority over this resolver when chained after
 * {@link EnvSecretResolver} in {@link SecretResolver#defaultResolver()} -- a {@code .env} file is
 * purely a local convenience, never a substitute for real secret management.
 */
public final class DotenvSecretResolver implements SecretResolver {

    private final Map<String, String> entries;

    public DotenvSecretResolver(Path envFile) {
        Objects.requireNonNull(envFile, "envFile must not be null");
        this.entries = parse(envFile);
    }

    @Override
    public Optional<String> resolve(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(secretKey));
    }

    /** All parsed key/value pairs, for callers that need to merge them into a wider environment map. */
    public Map<String, String> entries() {
        return entries;
    }

    private static Map<String, String> parse(Path envFile) {
        if (!Files.isRegularFile(envFile)) {
            return Map.of();
        }
        Map<String, String> parsed = new HashMap<>();
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("export ")) {
                    trimmed = trimmed.substring("export ".length()).stripLeading();
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).strip();
                String value = unquote(trimmed.substring(eq + 1).strip());
                if (!key.isEmpty()) {
                    parsed.put(key, value);
                }
            }
        } catch (IOException e) {
            return Map.of();
        }
        return Map.copyOf(parsed);
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
