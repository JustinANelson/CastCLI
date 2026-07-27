package dev.justnels.castcli.doctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.justnels.castcli.config.HarnessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server providing health diagnostic endpoints ({@code /healthz},
 * {@code /readyz}, {@code /livez}) and Prometheus-formatted metrics ({@code /metrics}).
 */
public final class HealthHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthHttpServer.class);
    private final HttpServer server;
    private final HarnessConfig config;
    private final Path configPath;
    private final DoctorService doctorService;
    private final ObjectMapper mapper = new ObjectMapper();

    public HealthHttpServer(int port, HarnessConfig config, Path configPath) throws IOException {
        this(port, config, configPath, new DoctorService());
    }

    public HealthHttpServer(int port, HarnessConfig config, Path configPath, DoctorService doctorService) throws IOException {
        this.config = config;
        this.configPath = configPath;
        this.doctorService = doctorService;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        HealthHandler healthHandler = new HealthHandler();
        this.server.createContext("/healthz", healthHandler);
        this.server.createContext("/readyz", healthHandler);
        this.server.createContext("/livez", healthHandler);
        this.server.createContext("/metrics", new MetricsHandler());
    }

    public void start() {
        server.start();
        log.info("CastCLI Health HTTP Server listening on port {}", server.getAddress().getPort());
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        stop(0);
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
        log.info("CastCLI Health HTTP Server stopped");
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            DoctorReport report = doctorService.diagnose(config, configPath);
            int statusCode = report.isHealthy() ? 200 : 503;

            byte[] responseBytes = mapper.writeValueAsBytes(report);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            DoctorReport report = doctorService.diagnose(config, configPath);
            int healthStatusGauge = report.isHealthy() ? 1 : 0;

            Runtime runtime = Runtime.getRuntime();
            long totalMem = runtime.totalMemory();
            long freeMem = runtime.freeMemory();
            long maxMem = runtime.maxMemory();

            StringBuilder sb = new StringBuilder();
            sb.append("# HELP castcli_health_status Health status (1=healthy/degraded, 0=unhealthy)\n");
            sb.append("# TYPE castcli_health_status gauge\n");
            sb.append("castcli_health_status ").append(healthStatusGauge).append("\n");

            sb.append("# HELP castcli_jvm_memory_total_bytes Total JVM allocated memory in bytes\n");
            sb.append("# TYPE castcli_jvm_memory_total_bytes gauge\n");
            sb.append("castcli_jvm_memory_total_bytes ").append(totalMem).append("\n");

            sb.append("# HELP castcli_jvm_memory_free_bytes Free JVM memory in bytes\n");
            sb.append("# TYPE castcli_jvm_memory_free_bytes gauge\n");
            sb.append("castcli_jvm_memory_free_bytes ").append(freeMem).append("\n");

            sb.append("# HELP castcli_jvm_memory_max_bytes Maximum JVM memory limit in bytes\n");
            sb.append("# TYPE castcli_jvm_memory_max_bytes gauge\n");
            sb.append("castcli_jvm_memory_max_bytes ").append(maxMem).append("\n");

            byte[] responseBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
