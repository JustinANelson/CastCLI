package dev.justnels.castcli.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Structured cryptographic manifest representing cloud data egress.
 */
public record EgressManifest(
        String traceId,
        String timestamp,
        String providerId,
        String modelName,
        ContextClassification classification,
        int fileCount,
        long totalBytes,
        long estimatedTokens,
        String promptHash,
        List<String> fileHashes,
        boolean allowed) {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public EgressManifest {
        timestamp = timestamp == null ? Instant.now().toString() : timestamp;
        fileHashes = fileHashes == null ? List.of() : List.copyOf(fileHashes);
    }

    public Path saveTo(Path egressDir) throws IOException {
        Path targetDir = egressDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(targetDir)) {
            Files.createDirectories(targetDir);
        }
        String fileName = "manifest-" + (traceId != null ? traceId : "unknown") + ".json";
        Path targetFile = targetDir.resolve(fileName);
        Files.writeString(targetFile, MAPPER.writeValueAsString(this));
        return targetFile;
    }
}
