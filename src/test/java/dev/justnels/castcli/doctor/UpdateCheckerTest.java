package dev.justnels.castcli.doctor;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCheckerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void reportsUpdateAvailableWhenReleaseIsNewer() throws Exception {
        UpdateChecker checker = mockChecker("{\"tag_name\": \"v0.5.0\"}");

        UpdateChecker.Result result = checker.check("0.1.0-SNAPSHOT");

        assertThat(result.checked()).isTrue();
        assertThat(result.updateAvailable()).isTrue();
        assertThat(result.latestVersion()).isEqualTo("0.5.0");
    }

    @Test
    void reportsUpToDateWhenReleaseIsSameOrOlder() throws Exception {
        UpdateChecker checker = mockChecker("{\"tag_name\": \"v0.1.0\"}");

        UpdateChecker.Result result = checker.check("0.1.0");

        assertThat(result.checked()).isTrue();
        assertThat(result.updateAvailable()).isFalse();
    }

    @Test
    void gracefullyReportsUncheckedOnServerError() throws Exception {
        UpdateChecker checker = mockCheckerWithStatus(500, "{}");

        UpdateChecker.Result result = checker.check("0.1.0");

        assertThat(result.checked()).isFalse();
        assertThat(result.updateAvailable()).isFalse();
        assertThat(result.detail()).contains("500");
    }

    @Test
    void gracefullyReportsUncheckedOnMalformedResponse() throws Exception {
        UpdateChecker checker = mockChecker("not json");

        UpdateChecker.Result result = checker.check("0.1.0");

        assertThat(result.checked()).isFalse();
        assertThat(result.updateAvailable()).isFalse();
    }

    private UpdateChecker mockChecker(String body) throws Exception {
        return mockCheckerWithStatus(200, body);
    }

    private UpdateChecker mockCheckerWithStatus(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> bodyRef = new AtomicReference<>(body);
        server.createContext("/", exchange -> {
            byte[] response = bodyRef.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.setExecutor(null);
        server.start();
        return new UpdateChecker(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
    }
}
