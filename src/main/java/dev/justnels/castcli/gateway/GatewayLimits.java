package dev.justnels.castcli.gateway;

import dev.justnels.castcli.config.HarnessConfig;

import java.time.Duration;

public record GatewayLimits(long maxRequestBytes, int maxConcurrentRequests, int maxConcurrentStreams,
                            long queueWaitMillis, int maxMessages, int maxTools, int maxJsonDepth,
                            int maxStringChars) {
    public GatewayLimits {
        if (maxRequestBytes < 1 || maxConcurrentRequests < 1 || maxConcurrentStreams < 1
                || queueWaitMillis < 0 || maxMessages < 1 || maxTools < 1 || maxJsonDepth < 1
                || maxStringChars < 1) {
            throw new IllegalArgumentException("gateway limits must be positive");
        }
    }

    public static GatewayLimits defaults(HarnessConfig config) {
        int providerConcurrency = Math.max(1, config.reliability().maxConcurrentRequests());
        return new GatewayLimits(1_048_576, Math.max(4, providerConcurrency * 2),
                providerConcurrency, Duration.ofSeconds(2).toMillis(), 256, 128, 100, 262_144);
    }
}
