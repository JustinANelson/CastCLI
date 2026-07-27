package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ProviderConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Explicit readiness probe for OpenAI-compatible provider /models endpoints. */
public final class ProviderHealthChecker {
    public record Result(boolean ready, int statusCode, long latencyMs, String detail) { }
    private final HttpClient client;

    public ProviderHealthChecker() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Result check(ProviderConfig provider) {
        if (!provider.enabled()) return new Result(false, 0, 0, "disabled");
        if (!provider.credentialsAvailable()) return new Result(false, 0, 0, "credentials missing");
        long start = System.nanoTime();
        try {
            URI base = URI.create(provider.baseUrl().endsWith("/") ? provider.baseUrl() : provider.baseUrl() + "/");
            HttpRequest request = HttpRequest.newBuilder(base.resolve("models"))
                    .timeout(Duration.ofSeconds(Math.min(10, provider.timeoutSeconds())))
                    .header("Authorization", "Bearer " + provider.resolvedApiKey())
                    .GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
            boolean ready = response.statusCode() >= 200 && response.statusCode() < 300;
            return new Result(ready, response.statusCode(), latency, ready ? "ready" : "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, Duration.ofNanos(System.nanoTime() - start).toMillis(), "interrupted");
        } catch (Exception e) {
            return new Result(false, 0, Duration.ofNanos(System.nanoTime() - start).toMillis(), e.getClass().getSimpleName());
        }
    }
}
