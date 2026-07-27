package dev.justnels.castcli.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves secrets from local disk files or mounted secret volume paths
 * (e.g. Kubernetes secret mounts at {@code /var/run/secrets/}).
 */
public final class FileSecretResolver implements SecretResolver {

    private final Path secretsDirectory;

    public FileSecretResolver(Path secretsDirectory) {
        this.secretsDirectory = Objects.requireNonNull(secretsDirectory, "secretsDirectory must not be null");
    }

    @Override
    public Optional<String> resolve(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return Optional.empty();
        }

        Path secretFile = secretsDirectory.resolve(secretKey).normalize();
        if (!secretFile.startsWith(secretsDirectory.normalize())) {
            // Prevent directory traversal attacks
            return Optional.empty();
        }

        if (!Files.isRegularFile(secretFile)) {
            return Optional.empty();
        }

        try {
            String content = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
