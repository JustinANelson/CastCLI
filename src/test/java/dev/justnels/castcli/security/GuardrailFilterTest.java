package dev.justnels.castcli.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GuardrailFilterTest {

    @Test
    void redactsCreditCardNumbers() {
        String input = "Payment processed with card 4532015589123456.";
        String filtered = GuardrailFilter.filter(input);
        assertThat(filtered).doesNotContain("4532015589123456");
        assertThat(filtered).contains("[REDACTED_CREDIT_CARD]");
    }

    @Test
    void redactsSocialSecurityNumbers() {
        String input = "User SSN is 123-45-6789.";
        String filtered = GuardrailFilter.filter(input);
        assertThat(filtered).doesNotContain("123-45-6789");
        assertThat(filtered).contains("[REDACTED_SSN]");
    }

    @Test
    void redactsJwtTokens() {
        String input = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String filtered = GuardrailFilter.filter(input);
        assertThat(filtered).contains("[REDACTED_JWT_TOKEN]");
    }

    @Test
    void redactsAwsKeys() {
        String input = "AWS Key: AKIAIOSFODNN7EXAMPLE";
        String filtered = GuardrailFilter.filter(input);
        assertThat(filtered).contains("[REDACTED_AWS_KEY]");
    }

    @Test
    void redactsGitHubTokens() {
        String input = "GitHub token ghp_123456789012345678901234567890123456";
        String filtered = GuardrailFilter.filter(input);
        assertThat(filtered).doesNotContain("ghp_123456789012345678901234567890123456");
        assertThat(filtered).contains("[REDACTED_GITHUB_TOKEN]");
    }

    @Test
    void redactsSlackTokens() {
        String input = "Slack token xoxb-123456789012-1234567890123-4567890abcdef123456";
        String filtered = GuardrailFilter.filter(input);
        assertThat(filtered).doesNotContain("xoxb-123456789012-1234567890123-4567890abcdef123456");
        assertThat(filtered).contains("[REDACTED_SLACK_TOKEN]");
    }

    @Test
    void passesCleanTextUnmodified() {
        String input = "Standard LLM output with no sensitive data.";
        assertThat(GuardrailFilter.filter(input)).isEqualTo(input);
    }

    @Test
    void leavesLegitimateCodeMentioningKeyTokenOrSecretVariableNamesIntact() {
        // Regression guard: filter() must not delegate to observability.SecretRedactor's broad
        // "key/token/secret/password: <value>" pattern, which would mangle ordinary code answers.
        String input = "private String apiKey = \"placeholder\";\nheaders.put(\"Authorization\", token);";
        assertThat(GuardrailFilter.filter(input)).isEqualTo(input);
    }
}
