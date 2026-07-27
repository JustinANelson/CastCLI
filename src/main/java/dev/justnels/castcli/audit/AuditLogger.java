package dev.justnels.castcli.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.observability.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public final class AuditLogger {
    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static volatile AuditLogger instance;

    private static final long DEFAULT_MAX_BYTES = 10 * 1024 * 1024L; // 10MB default limit

    private final Path auditFilePath;
    private final boolean enabled;
    private final long maxFileBytes;

    public AuditLogger(Path auditFilePath, boolean enabled) {
        this(auditFilePath, enabled, DEFAULT_MAX_BYTES);
    }

    public AuditLogger(Path auditFilePath, boolean enabled, long maxFileBytes) {
        this.auditFilePath = auditFilePath == null ? Path.of(".cast/audit.jsonl") : auditFilePath.toAbsolutePath().normalize();
        this.enabled = enabled;
        this.maxFileBytes = maxFileBytes > 0 ? maxFileBytes : DEFAULT_MAX_BYTES;
    }

    public static AuditLogger getInstance() {
        if (instance == null) {
            synchronized (AuditLogger.class) {
                if (instance == null) {
                    instance = new AuditLogger(Path.of(".cast/audit.jsonl"), true);
                }
            }
        }
        return instance;
    }

    public static void initialize(Path auditFilePath, boolean enabled) {
        synchronized (AuditLogger.class) {
            instance = new AuditLogger(auditFilePath, enabled);
        }
    }

    public static void initialize(Path auditFilePath, boolean enabled, long maxFileBytes) {
        synchronized (AuditLogger.class) {
            instance = new AuditLogger(auditFilePath, enabled, maxFileBytes);
        }
    }

    public void log(AuditEvent event) {
        if (!enabled || event == null) {
            return;
        }

        Map<String, String> sanitizedMeta = new HashMap<>();
        if (event.metadata() != null) {
            event.metadata().forEach((k, v) -> sanitizedMeta.put(k, SecretRedactor.redact(v)));
        }

        AuditEvent redactedEvent = new AuditEvent(
                event.id(),
                event.timestamp(),
                event.eventType(),
                event.actor(),
                SecretRedactor.redact(event.action()),
                SecretRedactor.redact(event.target()),
                event.status(),
                sanitizedMeta
        );

        try {
            String jsonLine = mapper.writeValueAsString(redactedEvent) + System.lineSeparator();
            synchronized (this) {
                if (auditFilePath.getParent() != null && !Files.exists(auditFilePath.getParent())) {
                    Files.createDirectories(auditFilePath.getParent());
                }
                rotateIfFull();
                Files.writeString(auditFilePath, jsonLine, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.error("Failed to write audit event to {}: {}", auditFilePath, e.getMessage());
        }
    }

    private void rotateIfFull() {
        try {
            if (Files.exists(auditFilePath) && Files.size(auditFilePath) >= maxFileBytes) {
                Path rotated = Path.of(auditFilePath.toString() + ".1");
                Files.move(auditFilePath, rotated, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to rotate audit log file {}: {}", auditFilePath, e.getMessage());
        }
    }

    public void log(String eventType, String actor, String action, String target, String status, Map<String, String> metadata) {
        log(AuditEvent.create(eventType, actor, action, target, status, metadata));
    }

    public Path getAuditFilePath() {
        return auditFilePath;
    }
}
