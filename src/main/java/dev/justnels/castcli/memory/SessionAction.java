package dev.justnels.castcli.memory;

/** Represents a single recorded action or step within an agent/session lifecycle. */
public record SessionAction(
        String sessionId,
        String agentRole,
        String action,
        String details,
        long timestamp) {

    public SessionAction {
        sessionId = textOrDefault(sessionId, "default-session");
        agentRole = textOrDefault(agentRole, "Agent");
        action = textOrDefault(action, "action");
        details = textOrDefault(details, "");
        if (timestamp <= 0) {
            timestamp = System.currentTimeMillis();
        }
    }

    public SessionAction(String sessionId, String agentRole, String action, String details) {
        this(sessionId, agentRole, action, details, System.currentTimeMillis());
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
