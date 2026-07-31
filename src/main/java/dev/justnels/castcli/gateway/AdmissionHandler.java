package dev.justnels.castcli.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class AdmissionHandler {
    private final GatewayLimits limits;
    private final GatewayMetrics metrics;
    private final ObjectMapper mapper;
    private final Semaphore permits;

    AdmissionHandler(GatewayLimits limits, GatewayMetrics metrics, ObjectMapper mapper) {
        this.limits = limits;
        this.metrics = metrics;
        this.mapper = mapper;
        this.permits = new Semaphore(limits.maxConcurrentRequests(), true);
    }

    private boolean acquire() {
        metrics.queued(1);
        try {
            return permits.tryAcquire(limits.queueWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            metrics.queued(-1);
        }
    }

    HttpHandler wrap(HttpHandler delegate) {
        return exchange -> handle(exchange, delegate);
    }

    private void handle(HttpExchange exchange, HttpHandler delegate) throws IOException {
        if (!acquire()) {
            reject(exchange);
            return;
        }
        metrics.accepted();
        try {
            delegate.handle(exchange);
        } finally {
            metrics.completed();
            permits.release();
        }
    }

    private void reject(HttpExchange exchange) throws IOException {
        metrics.rejected();
        exchange.getResponseHeaders().set("Retry-After", "1");
        GatewayErrors.send(exchange, mapper, 503, "server_overloaded",
                "Gateway concurrency limit reached; retry later.");
    }
}
