package dev.justnels.castcli.security;

import java.util.Optional;

/**
 * Strategy interface for dynamically resolving sensitive credentials, API keys,
 * and security tokens from environment variables, secret volume files, or external KMS stores.
 */
@FunctionalInterface
public interface SecretResolver {

    /**
     * Resolves a secret value by key or environment variable reference.
     *
     * @param secretKey reference name or key identifier
     * @return an {@link Optional} containing the resolved secret, or empty if not found
     */
    Optional<String> resolve(String secretKey);

    /**
     * The default secret resolver: real environment variables (highest priority), then a
     * {@code .env} file in the current working directory, then JVM system properties. Cached --
     * this is on the hot path for provider credential checks (e.g. per-request routing), and the
     * {@code .env} file is read once rather than re-parsed on every call.
     */
    static SecretResolver defaultResolver() {
        return DefaultResolverHolder.INSTANCE;
    }

    final class DefaultResolverHolder {
        static final SecretResolver INSTANCE = new CompositeSecretResolver(
                new EnvSecretResolver(),
                new DotenvSecretResolver(java.nio.file.Path.of(".env")),
                key -> Optional.ofNullable(System.getProperty(key))
        );

        private DefaultResolverHolder() {
        }
    }
}
