package dev.justnels.castcli.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ShutdownHookManagerTest {

    @Test
    void registersAndExecutesShutdownHooks() {
        AtomicBoolean closed = new AtomicBoolean(false);
        ShutdownHookManager manager = ShutdownHookManager.getInstance();

        manager.register(() -> closed.set(true));
        assertThat(manager.registeredCount()).isGreaterThanOrEqualTo(1);

        manager.performShutdown();
        assertThat(closed.get()).isTrue();
    }
}
