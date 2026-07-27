package dev.justnels.castcli.memory;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-local security context managing tenant isolation and authorization roles for shared memory and tools.
 */
public final class TenantSecurityContext {
    public enum Role {
        READ, WRITE, ADMIN
    }

    public static final String GLOBAL_TENANT_ID = "global";
    public static final TenantSecurityContext SYSTEM = new TenantSecurityContext(GLOBAL_TENANT_ID, Set.of(Role.READ, Role.WRITE, Role.ADMIN));

    private static final ThreadLocal<TenantSecurityContext> CONTEXT_HOLDER = ThreadLocal.withInitial(() -> SYSTEM);

    private final String tenantId;
    private final Set<Role> roles;

    public TenantSecurityContext(String tenantId, Set<Role> roles) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId cannot be null").trim().toLowerCase();
        this.roles = roles != null ? Set.copyOf(roles) : Collections.emptySet();
    }

    public static TenantSecurityContext current() {
        return CONTEXT_HOLDER.get();
    }

    public static void setCurrent(TenantSecurityContext context) {
        if (context == null) {
            CONTEXT_HOLDER.remove();
        } else {
            CONTEXT_HOLDER.set(context);
        }
    }

    public static void clear() {
        CONTEXT_HOLDER.remove();
    }

    public String getTenantId() {
        return tenantId;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role) || roles.contains(Role.ADMIN);
    }

    /**
     * Validates that the current context is authorized to read memory belonging to targetTenantId.
     */
    public void validateReadAccess(String targetTenantId) {
        if (!hasRole(Role.READ)) {
            throw new SecurityException("TenantSecurityContext: Read permission denied for tenant '" + tenantId + "'");
        }
        if (!isGlobalOrSystem() && !tenantId.equalsIgnoreCase(targetTenantId) && !GLOBAL_TENANT_ID.equalsIgnoreCase(targetTenantId)) {
            throw new SecurityException(String.format(
                    "TenantSecurityContext: Cross-tenant read access denied. Context tenant '%s' cannot access tenant '%s'",
                    tenantId, targetTenantId));
        }
    }

    /**
     * Validates that the current context is authorized to create/modify memory for targetTenantId.
     */
    public void validateWriteAccess(String targetTenantId) {
        if (!hasRole(Role.WRITE)) {
            throw new SecurityException("TenantSecurityContext: Write permission denied for tenant '" + tenantId + "'");
        }
        if (!isGlobalOrSystem() && !tenantId.equalsIgnoreCase(targetTenantId)) {
            throw new SecurityException(String.format(
                    "TenantSecurityContext: Cross-tenant write access denied. Context tenant '%s' cannot modify tenant '%s'",
                    tenantId, targetTenantId));
        }
    }

    private boolean isGlobalOrSystem() {
        return GLOBAL_TENANT_ID.equalsIgnoreCase(tenantId) || hasRole(Role.ADMIN);
    }
}
