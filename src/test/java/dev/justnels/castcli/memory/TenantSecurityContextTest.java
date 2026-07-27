package dev.justnels.castcli.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantSecurityContextTest {

    @AfterEach
    void tearDown() {
        TenantSecurityContext.clear();
    }

    @Test
    @DisplayName("Defaults to SYSTEM context with admin rights")
    void defaultsToSystemContext() {
        TenantSecurityContext current = TenantSecurityContext.current();
        assertThat(current.getTenantId()).isEqualTo("global");
        assertThat(current.hasRole(TenantSecurityContext.Role.ADMIN)).isTrue();
    }

    @Test
    @DisplayName("Allows tenant to access own data and global data")
    void allowsTenantAccessToOwnAndGlobal() {
        TenantSecurityContext tenantCtx = new TenantSecurityContext("tenant-a", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE));
        TenantSecurityContext.setCurrent(tenantCtx);

        tenantCtx.validateReadAccess("tenant-a");
        tenantCtx.validateReadAccess("global");
        tenantCtx.validateWriteAccess("tenant-a");
    }

    @Test
    @DisplayName("Denies tenant access to another tenant's data")
    void deniesCrossTenantAccess() {
        TenantSecurityContext tenantCtx = new TenantSecurityContext("tenant-a", Set.of(TenantSecurityContext.Role.READ, TenantSecurityContext.Role.WRITE));
        TenantSecurityContext.setCurrent(tenantCtx);

        assertThatThrownBy(() -> tenantCtx.validateReadAccess("tenant-b"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-tenant read access denied");

        assertThatThrownBy(() -> tenantCtx.validateWriteAccess("tenant-b"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-tenant write access denied");
    }

    @Test
    @DisplayName("Enforces missing role permissions")
    void enforcesRolePermissions() {
        TenantSecurityContext readOnlyCtx = new TenantSecurityContext("tenant-a", Set.of(TenantSecurityContext.Role.READ));
        TenantSecurityContext.setCurrent(readOnlyCtx);

        readOnlyCtx.validateReadAccess("tenant-a");

        assertThatThrownBy(() -> readOnlyCtx.validateWriteAccess("tenant-a"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Write permission denied");
    }
}
