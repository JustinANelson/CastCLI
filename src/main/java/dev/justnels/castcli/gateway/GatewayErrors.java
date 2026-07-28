package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Maps gateway failures onto the OpenAI {@code {"error": {...}}} response shape. */
final class GatewayErrors {

    private GatewayErrors() {
    }

    static void send(HttpExchange exchange, ObjectMapper mapper, int status, String type, String message)
            throws IOException {
        ObjectNode error = mapper.createObjectNode();
        ObjectNode body = error.putObject("error");
        body.put("message", message);
        body.put("type", type);
        body.putNull("param");
        body.put("code", status);
        byte[] bytes = mapper.writeValueAsBytes(error);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void methodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET, POST");
        byte[] bytes = "".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(405, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
