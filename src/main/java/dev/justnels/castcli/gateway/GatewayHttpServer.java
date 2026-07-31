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
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ExecutorService executor;
    private final GatewayMetrics metrics;
    private final AtomicBoolean closed = new AtomicBoolean();

    public GatewayHttpServer(String bindAddress, int port, HarnessConfig config, String bearerToken) throws IOException {
        this(bindAddress, port, config, bearerToken, new HarnessOrchestrator(config));
    }

    public GatewayHttpServer(String bindAddress, int port, HarnessConfig config, String bearerToken,
                             GatewayLimits limits) throws IOException {
        this(bindAddress, port, config, bearerToken, new HarnessOrchestrator(config), limits);
    }

    GatewayHttpServer(String bindAddress, int port, HarnessConfig config, String bearerToken,
                       HarnessOrchestrator orchestrator) throws IOException {
        this(bindAddress, port, config, bearerToken, orchestrator, GatewayLimits.defaults(config));
    }

    public GatewayHttpServer(String bindAddress, int port, HarnessConfig config, String bearerToken,
                             HarnessOrchestrator orchestrator, GatewayLimits limits) throws IOException {
        if (!isLoopback(bindAddress) && (bearerToken == null || bearerToken.isBlank())) {
            throw new IllegalArgumentException(
                    "Refusing to bind the gateway to non-loopback address '" + bindAddress
                            + "' without a --token; the gateway must fail closed for non-loopback use.");
        }
        Objects.requireNonNull(limits, "limits must not be null");
        ObjectMapper mapper = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
                .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                        .maxNestingDepth(limits.maxJsonDepth())
                        .maxStringLength(limits.maxStringChars())
                        .build()).build());
        this.metrics = new GatewayMetrics();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 128);
        this.server.setExecutor(executor);
        var admission = new AdmissionHandler(limits, metrics, mapper);
        this.server.createContext("/v1/chat/completions",
                admission.wrap(new AuthenticatingHandler(bearerToken,
                        new ChatCompletionsHandler(orchestrator, mapper, limits, metrics), mapper)));
        this.server.createContext("/v1/models",
                admission.wrap(new AuthenticatingHandler(bearerToken, new ModelsHandler(config, mapper), mapper)));
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

    public GatewayMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(1);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("CastCLI OpenAI-compatible gateway stopped");
    }
}
