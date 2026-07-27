package dev.justnels.castcli.security;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chains multiple {@link SecretResolver} implementations in priority order,
 * returning the first resolved non-empty secret.
 */
public final class CompositeSecretResolver implements SecretResolver {

    private final List<SecretResolver> resolvers;

    public CompositeSecretResolver(SecretResolver... resolvers) {
        this(List.of(resolvers));
    }

    public CompositeSecretResolver(List<SecretResolver> resolvers) {
        this.resolvers = List.copyOf(Objects.requireNonNull(resolvers, "resolvers list must not be null"));
    }

    @Override
    public Optional<String> resolve(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return Optional.empty();
        }

        for (SecretResolver resolver : resolvers) {
            Optional<String> secret = resolver.resolve(secretKey);
            if (secret.isPresent()) {
                return secret;
            }
        }
        return Optional.empty();
    }
}
