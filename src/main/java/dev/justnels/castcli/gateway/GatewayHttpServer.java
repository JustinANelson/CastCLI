package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;

/**
 * Phase-1 OpenAI-compatible inbound gateway (R-001): serves {@code /v1/chat/completions}
 * (non-streaming, no client-tool passthrough) and {@code /v1/models} so an existing OpenAI-SDK
 * client can route a chat turn through CastCLI's own local-first provider selection by changing
 * only its base URL. Streaming, client-side tool calls, and multi-turn history are explicit
 * follow-on phases -- not implemented here. Mirrors the binding/executor pattern of
 * {@link dev.justnels.castcli.doctor.HealthHttpServer}.
 */
public final class GatewayHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayHttpServer.class);

    private final HttpServer server;

    public GatewayHttpServer(String bindAddress, int port, HarnessConfig config, String bearerToken) throws IOException {
        this(bindAddress, port, config, bearerToken, new HarnessOrchestrator(config));
    }

    GatewayHttpServer(String bindAddress, int port, HarnessConfig config, String bearerToken,
                       HarnessOrchestrator orchestrator) throws IOException {
        if (!isLoopback(bindAddress) && (bearerToken == null || bearerToken.isBlank())) {
            throw new IllegalArgumentException(
                    "Refusing to bind the gateway to non-loopback address '" + bindAddress
                            + "' without a --token; the gateway must fail closed for non-loopback use.");
        }
        ObjectMapper mapper = new ObjectMapper();
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 128);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/v1/chat/completions",
                new AuthenticatingHandler(bearerToken, new ChatCompletionsHandler(orchestrator, mapper), mapper));
        this.server.createContext("/v1/models",
                new AuthenticatingHandler(bearerToken, new ModelsHandler(config, mapper), mapper));
    }

    private static boolean isLoopback(String bindAddress) {
        try {
            return InetAddress.getByName(bindAddress).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public void start() {
        server.start();
        log.info("CastCLI OpenAI-compatible gateway listening on {}:{}",
                server.getAddress().getHostString(), server.getAddress().getPort());
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public InetSocketAddress getAddress() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
        log.info("CastCLI OpenAI-compatible gateway stopped");
    }
}
