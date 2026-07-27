package dev.justnels.castcli.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLoggerTest {
    @TempDir
    Path tempDir;

    private Path auditFile;
    private AuditLogger logger;

    @BeforeEach
    void setUp() {
        auditFile = tempDir.resolve("audit.jsonl");
        logger = new AuditLogger(auditFile, true);
    }

    @Test
    void logsEventToDiskInJsonlFormat() throws IOException {
        logger.log("FILE_WRITE", "tester", "writeWorkspaceFile", "src/Main.java", "SUCCESS", Map.of("bytes", "100"));

        assertThat(Files.exists(auditFile)).isTrue();
        List<String> lines = Files.readAllLines(auditFile);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("\"eventType\":\"FILE_WRITE\"")
                .contains("\"actor\":\"tester\"")
                .contains("\"target\":\"src/Main.java\"")
                .contains("\"status\":\"SUCCESS\"");
    }

    @Test
    void redactsSensitiveKeysInMetadataAndAction() throws IOException {
        logger.log("MODEL_ROUTING", "user", "call", "sk-1234567890123456789012345", "SUCCESS", Map.of("api_key", "gsk_1234567890123456789012345"));

        List<String> lines = Files.readAllLines(auditFile);
        assertThat(lines.get(0)).doesNotContain("sk-1234567890")
                .doesNotContain("gsk_1234567890")
                .contains("[REDACTED_KEY]");
    }

    @Test
    void respectsDisabledFlag() {
        AuditLogger disabledLogger = new AuditLogger(auditFile, false);
        disabledLogger.log("FILE_WRITE", "tester", "action", "target", "SUCCESS", Map.of());

        assertThat(Files.exists(auditFile)).isFalse();
    }

    @Test
    void rotatesFileWhenExceedingMaxBytes() throws IOException {
        AuditLogger rotatingLogger = new AuditLogger(auditFile, true, 100);
        rotatingLogger.log("FILE_WRITE", "tester", "action1", "target1", "SUCCESS", Map.of());
        assertThat(Files.exists(auditFile)).isTrue();

        Path rotatedFile = Path.of(auditFile.toString() + ".1");
        assertThat(Files.exists(rotatedFile)).isFalse();

        rotatingLogger.log("FILE_WRITE", "tester", "action2", "target2", "SUCCESS", Map.of());
        assertThat(Files.exists(rotatedFile)).isTrue();
    }
}
