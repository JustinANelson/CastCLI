package dev.justnels.castcli.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantMemoryStoreTest {

    private InMemoryMemoryStore rawStore;
    private TenantMemoryStore tenantStore;

    @BeforeEach
    void setUp() {
        rawStore = new InMemoryMemoryStore();
        tenantStore = new TenantMemoryStore(rawStore);
        TenantSecurityContext.setCurrent(TenantSecurityContext.SYSTEM);
    }

    @AfterEach
    void tearDown() {
        TenantSecurityContext.clear();
    }

    @Test
    void putAndGetWithinSameTenantContext() {
        TenantSecurityContext tenantA = new TenantSecurityContext("tenant-a", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE));
        TenantSecurityContext.setCurrent(tenantA);

        MemoryDraft draft = MemoryDraft.shared("tenant-a", "key1", "val1", "AgentA");
        MemoryEntry entry = tenantStore.put(draft);

        assertThat(entry.namespace()).isEqualTo("tenant-a");

        Optional<MemoryEntry> retrieved = tenantStore.get(entry.id());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().content()).isEqualTo("val1");
    }

    @Test
    void preventsCrossTenantAccess() {
        TenantSecurityContext tenantA = new TenantSecurityContext("tenant-a", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE));
        TenantSecurityContext.setCurrent(tenantA);

        MemoryDraft draft = MemoryDraft.shared("tenant-a", "key1", "val1", "AgentA");
        MemoryEntry entry = tenantStore.put(draft);

        TenantSecurityContext tenantB = new TenantSecurityContext("tenant-b", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE));
        TenantSecurityContext.setCurrent(tenantB);

        // tenantB cannot read tenantA's entry
        assertThat(tenantStore.get(entry.id())).isEmpty();

        // tenantB cannot delete tenantA's entry
        assertThatThrownBy(() -> tenantStore.delete(entry.id(), entry.version()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void adminCanAccessAndPurgeAllTenants() {
        TenantSecurityContext tenantA = new TenantSecurityContext("tenant-a", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE));
        TenantSecurityContext.setCurrent(tenantA);
        MemoryEntry entryA = tenantStore.put(MemoryDraft.shared("tenant-a", "keyA", "valA", "AgentA"));

        TenantSecurityContext admin = new TenantSecurityContext("admin-user", Set.of(TenantSecurityContext.Role.ADMIN));
        TenantSecurityContext.setCurrent(admin);

        assertThat(tenantStore.get(entryA.id())).isPresent();
        assertThat(tenantStore.purgeExpired()).isZero();
    }

    @Test
    void nonAdminCannotPurge() {
        TenantSecurityContext tenantA = new TenantSecurityContext("tenant-a", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE));
        TenantSecurityContext.setCurrent(tenantA);

        assertThatThrownBy(() -> tenantStore.purgeExpired())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("ADMIN role");
    }

    @Test
    void searchAndListFilterUnallowedEntries() {
        TenantSecurityContext.setCurrent(new TenantSecurityContext("tenant-a", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE)));
        tenantStore.put(MemoryDraft.shared("tenant-a", "keyA", "valA", "AgentA"));

        TenantSecurityContext.setCurrent(new TenantSecurityContext("tenant-b", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE)));
        tenantStore.put(MemoryDraft.shared("tenant-b", "keyB", "valB", "AgentB"));

        // Searching as Tenant B returns only B's memory
        List<MemoryEntry> resultsB = tenantStore.search(new MemoryQuery("val", List.of(), null, List.of(), 10));
        assertThat(resultsB).hasSize(1);
        assertThat(resultsB.get(0).namespace()).isEqualTo("tenant-b");
    }
}
