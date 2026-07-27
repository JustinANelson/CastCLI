package dev.justnels.castcli.lifecycle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages graceful shutdown hook registration to ensure resources (telemetry, databases,
 * executor pools) are properly flushed and closed upon JVM termination.
 */
public final class ShutdownHookManager {

    private static final ShutdownHookManager INSTANCE = new ShutdownHookManager();
    private final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean shutdownCompleted = new AtomicBoolean(false);

    private ShutdownHookManager() {
    }

    public static ShutdownHookManager getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a resource to be closed cleanly during JVM shutdown.
     *
     * @param resource AutoCloseable resource instance
     */
    public void register(AutoCloseable resource) {
        if (resource != null) {
            resources.add(resource);
            ensureHookRegistered();
        }
    }

    /**
     * Executes all registered cleanup tasks explicitly. Safe to call multiple times.
     */
    public void performShutdown() {
        if (shutdownCompleted.compareAndSet(false, true)) {
            for (AutoCloseable resource : resources) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // Retain shutdown sequence continuity
                }
            }
            resources.clear();
        }
    }

    private void ensureHookRegistered() {
        if (registered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::performShutdown, "castcli-shutdown-hook"));
        }
    }

    public int registeredCount() {
        return resources.size();
    }
}
