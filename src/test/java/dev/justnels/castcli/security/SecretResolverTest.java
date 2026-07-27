package dev.justnels.castcli.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SecretResolverTest {

    @Test
    void envSecretResolverResolvesFromEnvironmentMap() {
        EnvSecretResolver resolver = new EnvSecretResolver(Map.of("OPENAI_KEY", "sk-test12345"));
        assertThat(resolver.resolve("OPENAI_KEY")).contains("sk-test12345");
        assertThat(resolver.resolve("MISSING_KEY")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    void fileSecretResolverResolvesFromDiskDirectory(@TempDir Path tempDir) throws IOException {
        Path secretFile = tempDir.resolve("DB_PASSWORD");
        Files.writeString(secretFile, "super-secret-pass\n");

        FileSecretResolver resolver = new FileSecretResolver(tempDir);
        assertThat(resolver.resolve("DB_PASSWORD")).contains("super-secret-pass");
        assertThat(resolver.resolve("NON_EXISTENT")).isEmpty();
    }

    @Test
    void fileSecretResolverPreventsDirectoryTraversal(@TempDir Path tempDir) {
        FileSecretResolver resolver = new FileSecretResolver(tempDir);
        assertThat(resolver.resolve("../etc/passwd")).isEmpty();
    }

    @Test
    void compositeSecretResolverDelegatesInOrder() {
        SecretResolver first = key -> key.equals("PRIORITY_KEY") ? Optional.of("first-val") : Optional.empty();
        SecretResolver second = key -> Optional.of("fallback-val");

        CompositeSecretResolver composite = new CompositeSecretResolver(first, second);
        assertThat(composite.resolve("PRIORITY_KEY")).contains("first-val");
        assertThat(composite.resolve("OTHER_KEY")).contains("fallback-val");
    }

    @Test
    void dotenvSecretResolverParsesKeyValueLinesIgnoringCommentsAndBlankLines(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                # a comment
                OPENAI_API_KEY=sk-from-dotenv

                export QUOTED_KEY="quoted-value"
                SINGLE_QUOTED='single-value'
                """);

        DotenvSecretResolver resolver = new DotenvSecretResolver(envFile);

        assertThat(resolver.resolve("OPENAI_API_KEY")).contains("sk-from-dotenv");
        assertThat(resolver.resolve("QUOTED_KEY")).contains("quoted-value");
        assertThat(resolver.resolve("SINGLE_QUOTED")).contains("single-value");
        assertThat(resolver.resolve("MISSING")).isEmpty();
    }

    @Test
    void dotenvSecretResolverIsEmptyWhenFileDoesNotExist(@TempDir Path tempDir) {
        DotenvSecretResolver resolver = new DotenvSecretResolver(tempDir.resolve("does-not-exist.env"));

        assertThat(resolver.resolve("ANYTHING")).isEmpty();
        assertThat(resolver.entries()).isEmpty();
    }

    @Test
    void defaultResolverChainPrefersEnvOverDotenvOverSystemProperties(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "SHARED_KEY=from-dotenv\nDOTENV_ONLY_KEY=only-in-dotenv\n");

        SecretResolver chain = new CompositeSecretResolver(
                new EnvSecretResolver(Map.of("SHARED_KEY", "from-real-env")),
                new DotenvSecretResolver(envFile),
                key -> "PROPS_ONLY_KEY".equals(key) ? Optional.of("from-props") : Optional.empty());

        assertThat(chain.resolve("SHARED_KEY")).contains("from-real-env");
        assertThat(chain.resolve("DOTENV_ONLY_KEY")).contains("only-in-dotenv");
        assertThat(chain.resolve("PROPS_ONLY_KEY")).contains("from-props");
    }

    @Test
    void defaultResolverIsUsableAndCached() {
        assertThat(SecretResolver.defaultResolver()).isSameAs(SecretResolver.defaultResolver());
    }
}
