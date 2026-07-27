package dev.justnels.castcli.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.security.DotenvSecretResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class ConfigLoader {
    private static final java.util.regex.Pattern ENV_PATTERN = java.util.regex.Pattern.compile(
            "\\$\\{([a-zA-Z0-9_]+)(?::([^}]*))?\\}"
    );

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public HarnessConfig load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Configuration file not found: " + path.toAbsolutePath());
        }
        String jsonContent = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        String expandedJson = expandEnvironmentVariables(jsonContent, effectiveEnvironment());
        return mapper.readValue(expandedJson, HarnessConfig.class);
    }

    /** Real process environment variables, overlaid on top of any {@code .env} file in the working
     * directory -- a real environment variable always wins over a same-named {@code .env} entry. */
    private static Map<String, String> effectiveEnvironment() {
        Map<String, String> merged = new HashMap<>(new DotenvSecretResolver(Path.of(".env")).entries());
        merged.putAll(System.getenv());
        return merged;
    }

    public static String expandEnvironmentVariables(String content, java.util.Map<String, String> env) {
        if (content == null || content.isBlank() || env == null) {
            return content;
        }
        java.util.regex.Matcher matcher = ENV_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2);
            String envValue = env.get(varName);

            String replacement;
            if (envValue != null) {
                replacement = envValue;
            } else if (defaultValue != null) {
                replacement = defaultValue;
            } else {
                replacement = matcher.group(0);
            }
            // Escape any special characters for appendReplacement
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

