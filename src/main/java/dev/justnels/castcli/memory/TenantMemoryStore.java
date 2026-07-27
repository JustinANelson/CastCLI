package dev.justnels.castcli.memory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorates an underlying {@link MemoryStore} to enforce multi-tenant isolation
 * and role-based access control based on {@link TenantSecurityContext}.
 */
public final class TenantMemoryStore implements MemoryStore {

    private final MemoryStore delegate;

    public TenantMemoryStore(MemoryStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate MemoryStore must not be null");
    }

    @Override
    public MemoryEntry put(MemoryDraft draft) {
        Objects.requireNonNull(draft, "draft cannot be null");
        TenantSecurityContext context = TenantSecurityContext.current();
        
        MemoryDraft scopedDraft = draft;
        if (draft.namespace() == null || draft.namespace().isBlank() || draft.namespace().equalsIgnoreCase("project")) {
            if (!context.getTenantId().equalsIgnoreCase(TenantSecurityContext.GLOBAL_TENANT_ID)) {
                scopedDraft = new MemoryDraft(
                        context.getTenantId(),
                        draft.scope(),
                        draft.topic(),
                        draft.content(),
                        draft.author(),
                        draft.source(),
                        draft.tags(),
                        draft.importance(),
                        draft.confidence(),
                        draft.expiresAt(),
                        draft.readOnly(),
                        draft.supersedesId()
                );
            }
        }

        context.validateWriteAccess(scopedDraft.namespace());
        return delegate.put(scopedDraft);
    }

    @Override
    public MemoryEntry update(String id, int expectedVersion, MemoryDraft replacement) {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        TenantSecurityContext context = TenantSecurityContext.current();
        Optional<MemoryEntry> existingOpt = delegate.get(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Memory entry not found: " + id);
        }

        MemoryEntry existing = existingOpt.get();
        context.validateWriteAccess(existing.namespace());

        MemoryDraft scopedReplacement = replacement;
        if (replacement.namespace() == null || replacement.namespace().isBlank() || replacement.namespace().equalsIgnoreCase("project")) {
            scopedReplacement = new MemoryDraft(
                    existing.namespace(),
                    replacement.scope(),
                    replacement.topic(),
                    replacement.content(),
                    replacement.author(),
                    replacement.source(),
                    replacement.tags(),
                    replacement.importance(),
                    replacement.confidence(),
                    replacement.expiresAt(),
                    replacement.readOnly(),
                    replacement.supersedesId()
            );
        }

        context.validateWriteAccess(scopedReplacement.namespace());
        return delegate.update(id, expectedVersion, scopedReplacement);
    }

    @Override
    public Optional<MemoryEntry> get(String id) {
        Objects.requireNonNull(id, "id cannot be null");
        Optional<MemoryEntry> entryOpt = delegate.get(id);
        if (entryOpt.isEmpty()) {
            return Optional.empty();
        }

        MemoryEntry entry = entryOpt.get();
        try {
            TenantSecurityContext.current().validateReadAccess(entry.namespace());
            return entryOpt;
        } catch (SecurityException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<MemoryEntry> search(MemoryQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        List<MemoryEntry> rawResults = delegate.search(query);
        TenantSecurityContext context = TenantSecurityContext.current();

        return rawResults.stream()
                .filter(entry -> {
                    try {
                        context.validateReadAccess(entry.namespace());
                        return true;
                    } catch (SecurityException e) {
                        return false;
                    }
                })
                .toList();
    }

    @Override
    public List<MemoryEntry> list(String namespace, int limit) {
        List<MemoryEntry> rawResults = delegate.list(namespace, limit);
        TenantSecurityContext context = TenantSecurityContext.current();

        return rawResults.stream()
                .filter(entry -> {
                    try {
                        context.validateReadAccess(entry.namespace());
                        return true;
                    } catch (SecurityException e) {
                        return false;
                    }
                })
                .toList();
    }

    @Override
    public boolean delete(String id, int expectedVersion) {
        Objects.requireNonNull(id, "id cannot be null");
        Optional<MemoryEntry> existingOpt = delegate.get(id);
        if (existingOpt.isEmpty()) {
            return false;
        }

        MemoryEntry existing = existingOpt.get();
        TenantSecurityContext.current().validateWriteAccess(existing.namespace());
        return delegate.delete(id, expectedVersion);
    }

    @Override
    public int purgeExpired() {
        if (!TenantSecurityContext.current().hasRole(TenantSecurityContext.Role.ADMIN)) {
            throw new SecurityException("TenantMemoryStore: Purge operation requires ADMIN role");
        }
        return delegate.purgeExpired();
    }

    @Override
    public int purgeOlderThan(int retentionDays) {
        if (!TenantSecurityContext.current().hasRole(TenantSecurityContext.Role.ADMIN)) {
            throw new SecurityException("TenantMemoryStore: Purge operation requires ADMIN role");
        }
        return delegate.purgeOlderThan(retentionDays);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
