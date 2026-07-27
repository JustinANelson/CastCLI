package dev.justnels.castcli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.justnels.castcli.config.McpAuditConfig;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Cross-process-safe JSONL audit store used by the MCP server and reporting CLI. */
public final class McpUsageStore {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpUsageStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public static Path resolvePath(McpAuditConfig config, Path workspaceRoot) {
        Path configured = Path.of(config.path());
        return (configured.isAbsolute() ? configured : workspaceRoot.resolve(configured))
                .toAbsolutePath().normalize();
    }

    public Path path() {
        return path;
    }

    public synchronized void append(McpUsageRecord record) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            byte[] line = (mapper.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND); var lock = channel.lock()) {
                if (!lock.isValid()) throw new IllegalStateException("MCP usage audit file lock is invalid");
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(false);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to append MCP usage audit record to " + path, e);
        }
    }

    public List<McpUsageRecord> readSince(long sinceEpochMs) {
        if (!Files.exists(path)) return List.of();
        try {
            List<McpUsageRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                McpUsageRecord record = mapper.readValue(line, McpUsageRecord.class);
                if (record.timestampEpochMs() >= sinceEpochMs) records.add(record);
            }
            return List.copyOf(records);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read MCP usage audit records from " + path, e);
        }
    }
}
