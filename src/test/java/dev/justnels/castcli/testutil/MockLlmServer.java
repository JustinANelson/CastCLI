package dev.justnels.castcli.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight JDK HttpServer mock for testing downstream LLM provider interactions,
 * chaos scenarios (HTTP 429, 500, timeouts, malformed responses), and circuit-breaker behavior.
 */
public class MockLlmServer implements AutoCloseable {
    public enum ResponseMode {
        SUCCESS, RATE_LIMIT_429, SERVER_ERROR_500, TIMEOUT_DELAY, MALFORMED_JSON
    }

    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private volatile ResponseMode mode = ResponseMode.SUCCESS;
    private volatile String successMessage = "Mock response from server";
    private volatile long delayMs = 0;

    public MockLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new MockHandler());
        server.setExecutor(null);
        server.start();
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void setMode(ResponseMode mode) {
        this.mode = mode;
    }

    public void setSuccessMessage(String message) {
        this.successMessage = message;
    }

    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    public void resetRequestCount() {
        requestCount.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private class MockHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            requestCount.incrementAndGet();

            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            switch (mode) {
                case RATE_LIMIT_429 -> {
                    exchange.getResponseHeaders().set("Retry-After", "5");
                    byte[] response = "{\"error\": {\"message\": \"Rate limit exceeded\", \"type\": \"requests_exceeded\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, response.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                }
                case SERVER_ERROR_500 -> {
                    byte[] response = "{\"error\": {\"message\": \"Internal server failure\", \"type\": \"server_error\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, response.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                }
                case MALFORMED_JSON -> {
                    byte[] response = "{invalid_json_payload: [unclosed".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                }
                case SUCCESS -> {
                    String json = String.format("""
                            {
                              "id": "chatcmpl-mock123",
                              "object": "chat.completion",
                              "created": 1677858388,
                              "model": "mock-model",
                              "choices": [{
                                "index": 0,
                                "message": {
                                  "role": "assistant",
                                  "content": "%s"
                                },
                                "finish_reason": "stop"
                              }],
                              "usage": {
                                "prompt_tokens": 10,
                                "completion_tokens": 20,
                                "total_tokens": 30
                              }
                            }
                            """, successMessage);
                    byte[] response = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                }
            }
        }
    }
}
