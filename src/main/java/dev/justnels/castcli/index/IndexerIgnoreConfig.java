package dev.justnels.castcli.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration file format and loader for semantic search indexer ignore rules (.cast/index-ignore.json).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexerIgnoreConfig(
        @JsonProperty("includeGitIgnore") boolean includeGitIgnore,
        @JsonProperty("profiles") List<String> profiles,
        @JsonProperty("excludeGlobs") List<String> excludeGlobs) {

    public static final String CONFIG_FILE_NAME = ".cast/index-ignore.json";
    public static final String ALT_CONFIG_FILE_NAME = ".castignore";

    public static final List<String> SECURITY_EXCLUDE_GLOBS = List.of(
            "**/credentials.json", "**/credentials*.json", "**/credential*.json",
            "**/*secret*", "**/*password*", "**/*token*",
            "**/*.pem", "**/*.key", "**/*.crt", "**/*.cer", "**/*.p12", "**/*.pfx", "**/*.asc", "**/*.jks", "**/*.keystore",
            "**/.env", "**/.env.*", "**/*.env",
            "**/id_rsa*", "**/id_ed25519*", "**/id_dsa*", "**/id_ecdsa*", "**/*.ppk",
            "**/shadow", "**/passwd", "**/htpasswd", "**/auth.json", "**/netrc", "**/.netrc"
    );

    public static final List<String> VCS_BUILD_EXCLUDE_GLOBS = List.of(
            "**/.git/**", "**/.gitignore", "**/build/**", "**/.gradle/**", "**/.harness/**", "**/.cast/**", "**/node_modules/**",
            "**/dist/**", "**/target/**", "**/.idea/**", "**/.vs/**"
    );

    public static final List<String> DEFAULT_EXCLUDE_GLOBS = createDefaultExcludeGlobs();

    private static List<String> createDefaultExcludeGlobs() {
        List<String> combined = new ArrayList<>(VCS_BUILD_EXCLUDE_GLOBS);
        for (String pattern : SECURITY_EXCLUDE_GLOBS) {
            if (!combined.contains(pattern)) {
                combined.add(pattern);
            }
        }
        return List.copyOf(combined);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public IndexerIgnoreConfig {
        profiles = (profiles == null || profiles.isEmpty())
                ? List.of("security", "build_artifacts", "vcs")
                : List.copyOf(profiles);
        excludeGlobs = (excludeGlobs == null || excludeGlobs.isEmpty())
                ? DEFAULT_EXCLUDE_GLOBS
                : List.copyOf(excludeGlobs);
    }

    public static IndexerIgnoreConfig defaultConfig() {
        return new IndexerIgnoreConfig(true, List.of("security", "build_artifacts", "vcs"), DEFAULT_EXCLUDE_GLOBS);
    }

    /**
     * Ensures that the ignore config file exists in the target workspace. If neither .cast/index-ignore.json
     * nor .castignore exists, creates .cast/index-ignore.json with default settings.
     */
    public static Path ensureCreated(Path workspaceRoot) throws IOException {
        Path primaryPath = workspaceRoot.resolve(CONFIG_FILE_NAME).toAbsolutePath().normalize();
        Path altPath = workspaceRoot.resolve(ALT_CONFIG_FILE_NAME).toAbsolutePath().normalize();

        if (Files.isRegularFile(primaryPath)) {
            return primaryPath;
        }
        if (Files.isRegularFile(altPath)) {
            return altPath;
        }

        Files.createDirectories(primaryPath.getParent());
        IndexerIgnoreConfig defaultConfig = defaultConfig();
        MAPPER.writeValue(primaryPath.toFile(), defaultConfig);
        return primaryPath;
    }

    /**
     * Loads the ignore config from the workspace if present, or returns the default config if missing.
     */
    public static IndexerIgnoreConfig load(Path workspaceRoot) {
        Path primaryPath = workspaceRoot.resolve(CONFIG_FILE_NAME).toAbsolutePath().normalize();
        Path altPath = workspaceRoot.resolve(ALT_CONFIG_FILE_NAME).toAbsolutePath().normalize();

        Path toLoad = null;
        if (Files.isRegularFile(primaryPath)) {
            toLoad = primaryPath;
        } else if (Files.isRegularFile(altPath)) {
            toLoad = altPath;
        }

        if (toLoad != null) {
            try {
                return MAPPER.readValue(toLoad.toFile(), IndexerIgnoreConfig.class);
            } catch (IOException ignored) {
                // fall back to default config if file is unreadable or malformed
            }
        }
        return defaultConfig();
    }
}
