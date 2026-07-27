package dev.justnels.castcli.config;

public record RoutingConfig(
        int quickPromptMaxChars,
        boolean preferLocal,
        int maxContextChars,
        Integer maxReworkIterations) {
    public RoutingConfig {
        if (quickPromptMaxChars < 1) {
            throw new IllegalArgumentException("quickPromptMaxChars must be positive");
        }
        if (maxContextChars < 1) {
            maxContextChars = 12_000;
        }
        // Integer (not int): a JSON config that omits this field must still get the default rework
        // pass, distinct from a config that explicitly sets it to 0 to disable rework.
        if (maxReworkIterations == null || maxReworkIterations < 0) {
            maxReworkIterations = 1;
        }
    }

    public RoutingConfig(int quickPromptMaxChars, boolean preferLocal) {
        this(quickPromptMaxChars, preferLocal, 12_000, 1);
    }
}

