package dev.justnels.castcli.config;

/** Configuration for durable, shared project memory. */
public record MemoryConfig(
        boolean enabled,
        String databasePath,
        String defaultNamespace,
        int maxContextChars,
        int maxResults,
        int retentionDays) {

    public static final String DEFAULT_DATABASE_PATH = ".cast/memory/memory.db";

    public MemoryConfig {
        databasePath = textOrDefault(databasePath, DEFAULT_DATABASE_PATH);
        defaultNamespace = textOrDefault(defaultNamespace, "project");
        if (maxContextChars < 1) maxContextChars = 4_000;
        if (maxResults < 1) maxResults = 8;
        if (retentionDays < 0) retentionDays = 0;
    }

    public static MemoryConfig disabled() {
        return new MemoryConfig(false, null, null, 4_000, 8, 0);
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
