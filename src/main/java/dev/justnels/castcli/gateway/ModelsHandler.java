package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ProviderConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;

/** Handles {@code GET /v1/models}, listing enabled providers in the OpenAI model-list shape. */
final class ModelsHandler implements HttpHandler {

    private final HarnessConfig config;
    private final ObjectMapper mapper;

    ModelsHandler(HarnessConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            GatewayErrors.methodNotAllowed(exchange);
            return;
        }

        long createdEpochSecond = Instant.now().getEpochSecond();
        ObjectNode response = mapper.createObjectNode();
        response.put("object", "list");
        var data = response.putArray("data");
        for (ProviderConfig provider : config.providers()) {
            if (!provider.enabled()) {
                continue;
            }
            ObjectNode model = data.addObject();
            model.put("id", provider.id());
            model.put("object", "model");
            model.put("created", createdEpochSecond);
            model.put("owned_by", "castcli");
        }

        byte[] bytes = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
