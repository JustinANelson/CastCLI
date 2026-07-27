package dev.justnels.castcli.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    void redactsOpenAiStyleApiKeys() {
        String input = "Using API key sk-123456789012345678901234567890 to authenticate.";
        String expected = "Using API key [REDACTED_KEY] to authenticate.";
        assertThat(SecretRedactor.redact(input)).isEqualTo(expected);
    }

    @Test
    void redactsBearerTokensAndHeaders() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
    }

    @Test
    void redactsHeaderSecrets() {
        String input = "api-key: my_secret_token_value_123";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("my_secret_token_value_123");
    }

    @Test
    void leavesNormalTextIntact() {
        String input = "Searching workspace for public static void main method";
        assertThat(SecretRedactor.redact(input)).isEqualTo(input);
    }
}
