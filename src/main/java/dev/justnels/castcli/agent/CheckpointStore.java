package dev.justnels.castcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.justnels.castcli.persistence.AtomicFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;

public final class CheckpointStore {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path checkpointDir;

    public CheckpointStore(Path checkpointDir) {
        this.checkpointDir = checkpointDir;
    }

    public Path pathFor(String goal) {
        return checkpointDir.resolve(fingerprint(goal) + ".json");
    }

    public Path save(Checkpoint checkpoint) throws IOException {
        Files.createDirectories(checkpointDir);
        Path path = pathFor(checkpoint.goal());
        AtomicFileWriter.write(path, mapper.writeValueAsBytes(checkpoint));
        return path;
    }

    public Optional<Checkpoint> load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            Path backup = AtomicFileWriter.backupPath(path);
            if (!Files.isRegularFile(backup)) return Optional.empty();
            return Optional.of(mapper.readValue(backup.toFile(), Checkpoint.class));
        }
        try {
            return Optional.of(mapper.readValue(path.toFile(), Checkpoint.class));
        } catch (IOException primaryFailure) {
            Path backup = AtomicFileWriter.backupPath(path);
            if (!Files.isRegularFile(backup)) throw primaryFailure;
            return Optional.of(mapper.readValue(backup.toFile(), Checkpoint.class));
        }
    }

    private static String fingerprint(String goal) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(goal.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(goal.hashCode());
        }
    }
}

