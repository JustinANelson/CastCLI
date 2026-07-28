package dev.justnels.castcli.doctor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Shared HTTP probing for a local Ollama instance, used by both {@link InitService} and
 * {@link DoctorService}. Retries once after a short delay on any failure -- Ollama's server can take a
 * moment to start accepting connections right after being launched, which a single tight-timeout attempt
 * can mistake for "not running" even though a follow-up call a second later succeeds. */
final class OllamaProbe {
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private OllamaProbe() {
    }

    static <T> HttpResponse<T> getWithRetry(
            HttpClient client, URI uri, Duration requestTimeout, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        try {
            return send(client, uri, requestTimeout, bodyHandler);
        } catch (IOException firstAttemptFailure) {
            Thread.sleep(RETRY_DELAY.toMillis());
            return send(client, uri, requestTimeout, bodyHandler);
        }
    }

    private static <T> HttpResponse<T> send(
            HttpClient client, URI uri, Duration requestTimeout, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build();
        return client.send(request, bodyHandler);
    }
}
