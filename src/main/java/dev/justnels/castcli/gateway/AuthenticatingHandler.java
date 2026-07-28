package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.justnels.castcli.security.BearerTokenAuth;

import java.io.IOException;

/**
 * Wraps a gateway endpoint handler with fail-closed bearer-token verification. When no token is
 * configured (loopback-only, developer convenience mode), every request is allowed through
 * unauthenticated -- {@link GatewayHttpServer} already refuses to construct with a non-loopback
 * bind and no token, so an unauthenticated wrap here only ever applies to loopback traffic.
 */
final class AuthenticatingHandler implements HttpHandler {

    private final String expectedToken;
    private final HttpHandler delegate;
    private final ObjectMapper mapper;

    AuthenticatingHandler(String expectedToken, HttpHandler delegate, ObjectMapper mapper) {
        this.expectedToken = expectedToken;
        this.delegate = delegate;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (expectedToken != null && !expectedToken.isBlank()) {
            String presented = BearerTokenAuth.extractToken(exchange.getRequestHeaders().getFirst("Authorization"));
            if (!BearerTokenAuth.matches(presented, expectedToken)) {
                GatewayErrors.send(exchange, mapper, 401, "invalid_api_key",
                        "Missing or invalid Authorization bearer token.");
                return;
            }
        }
        delegate.handle(exchange);
    }
}
