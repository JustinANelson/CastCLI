package dev.justnels.castcli.security;

import java.util.Map;

import java.util.Objects;

import java.util.Optional;

/**

 * Resolves secrets from process environment variables.

 */

public final class EnvSecretResolver implements SecretResolver {



    private final Map<String, String> environmentSupplier;



    public EnvSecretResolver() {

        this(System.getenv());

    }



    public EnvSecretResolver(Map<String, String> environmentSupplier) {

        this.environmentSupplier = Objects.requireNonNull(environmentSupplier, "environmentSupplier must not be null");

    }



    @Override

    public Optional<String> resolve(String secretKey) {

        if (secretKey == null || secretKey.isBlank()) {

            return Optional.empty();

        }

        String value = environmentSupplier.get(secretKey);

        if (value == null || value.isBlank()) {

            return Optional.empty();

        }

        return Optional.of(value);

    }

}

