package dev.justnels.castcli.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public record AuditEvent(
        String id,
        String timestamp,
        String eventType,
        String actor,
        String action,
        String target,
        String status,
        Map<String, String> metadata
) {
    public static final String TYPE_SECURITY_APPROVAL = "SECURITY_APPROVAL";
    public static final String TYPE_TOOL_EXECUTION = "TOOL_EXECUTION";
    public static final String TYPE_MODEL_ROUTING = "MODEL_ROUTING";
    public static final String TYPE_MEMORY_MUTATION = "MEMORY_MUTATION";
    public AuditEvent {
        if (id == null) id = java.util.UUID.randomUUID().toString();
        if (timestamp == null) timestamp = Instant.now().toString();
        if (eventType == null) eventType = "UNKNOWN";
        if (actor == null) actor = "system";
        if (action == null) action = "none";
        if (target == null) target = "";
        if (status == null) status = "INFO";
        if (metadata == null) metadata = Collections.emptyMap();
    }

    public static AuditEvent create(String eventType, String actor, String action, String target, String status, Map<String, String> metadata) {
        return new AuditEvent(java.util.UUID.randomUUID().toString(), Instant.now().toString(), eventType, actor, action, target, status, metadata);
    }
}
