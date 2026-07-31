package dev.justnels.castcli.gateway;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class GatewayMetrics {
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder oversized = new LongAdder();
    private final LongAdder requestBytes = new LongAdder();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();

    void accepted() { accepted.increment(); active.incrementAndGet(); }
    void completed() { active.decrementAndGet(); }
    void rejected() { rejected.increment(); }
    void oversized() { oversized.increment(); }
    void addRequestBytes(long bytes) { requestBytes.add(Math.max(0, bytes)); }
    void queued(int delta) { queued.addAndGet(delta); }

    Snapshot snapshot() {
        return new Snapshot(accepted.sum(), rejected.sum(), oversized.sum(), requestBytes.sum(),
                active.get(), queued.get());
    }

    public record Snapshot(long accepted, long rejected, long oversized, long requestBytes,
                           int active, int queued) { }
}
