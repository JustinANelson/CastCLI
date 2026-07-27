package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.ReliabilityConfig;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local live health used by routing; durable providers recover after a configured cooldown. */
public final class ProviderHealthRegistry {
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final ReliabilityConfig config;
    private final Clock clock;

    public ProviderHealthRegistry(ReliabilityConfig config) { this(config, Clock.systemUTC()); }
    ProviderHealthRegistry(ReliabilityConfig config, Clock clock) { this.config = config; this.clock = clock; }

    public boolean isAvailable(ProviderConfig provider) {
        return state(provider.id()).circuitOpenUntilMillis <= clock.millis();
    }

    public long observedLatencyMs(ProviderConfig provider) {
        double observed = state(provider.id()).ewmaLatencyMs;
        return observed <= 0 ? Math.max(1, provider.timeoutSeconds() * 500L) : Math.round(observed);
    }

    public int consecutiveFailures(ProviderConfig provider) { return state(provider.id()).consecutiveFailures; }

    public synchronized void recordSuccess(ProviderConfig provider, long durationMs) {
        State state = state(provider.id());
        state.consecutiveFailures = 0;
        state.circuitOpenUntilMillis = 0;
        state.ewmaLatencyMs = state.ewmaLatencyMs == 0 ? durationMs : state.ewmaLatencyMs * 0.8 + durationMs * 0.2;
    }

    public synchronized void recordFailure(ProviderConfig provider, FailureKind kind) {
        if (kind == FailureKind.POLICY || kind == FailureKind.CONTEXT_LENGTH) return;
        State state = state(provider.id());
        state.consecutiveFailures = kind == FailureKind.AUTHENTICATION
                ? Math.max(config.failureThreshold(), state.consecutiveFailures + 1)
                : state.consecutiveFailures + 1;
        if (state.consecutiveFailures >= config.failureThreshold()) {
            state.circuitOpenUntilMillis = clock.millis() + config.cooldownSeconds() * 1_000L;
        }
    }

    public synchronized void saveState(java.nio.file.Path path) throws java.io.IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (path.getParent() != null) {
            java.nio.file.Files.createDirectories(path.getParent());
        }
        mapper.writeValue(path.toFile(), states);
    }

    public synchronized void loadState(java.nio.file.Path path) throws java.io.IOException {
        if (!java.nio.file.Files.exists(path)) return;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, State> loaded = mapper.readValue(path.toFile(),
                mapper.getTypeFactory().constructMapType(Map.class, String.class, State.class));
        states.clear();
        states.putAll(loaded);
    }

    private State state(String id) { return states.computeIfAbsent(id, ignored -> new State()); }
    
    public static final class State {
        public int consecutiveFailures;
        public long circuitOpenUntilMillis;
        public double ewmaLatencyMs;

        public State() {}
        public State(int consecutiveFailures, long circuitOpenUntilMillis, double ewmaLatencyMs) {
            this.consecutiveFailures = consecutiveFailures;
            this.circuitOpenUntilMillis = circuitOpenUntilMillis;
            this.ewmaLatencyMs = ewmaLatencyMs;
        }
    }
}
