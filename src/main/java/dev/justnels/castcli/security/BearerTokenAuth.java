package dev.justnels.castcli.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time {@code Authorization: Bearer <token>} verification for locally hosted HTTP
 * surfaces (e.g. the OpenAI-compatible gateway) that must fail closed rather than merely warn.
 */
public final class BearerTokenAuth {

    private static final String PREFIX = "Bearer ";

    private BearerTokenAuth() {
    }

    /**
     * Extracts the bearer token from an {@code Authorization} header value, or {@code null} if
     * absent or not in the {@code Bearer <token>} form.
     */
    public static String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Compares a presented token against the expected token in constant time. Both a missing
     * presented token and a mismatched one return {@code false}; there is no partial-credit path.
     */
    public static boolean matches(String presented, String expected) {
        if (presented == null || expected == null) {
            return false;
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presentedBytes, expectedBytes);
    }
}
