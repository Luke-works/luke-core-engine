package com.luke.engine.tenant;

import jakarta.persistence.PrePersist;

/**
 * JPA entity listener that automatically sets tenantId
 * on any TenantAwareEntity before it's persisted.
 * No need to manually call setTenantId() in your controller.
 */
public class TenantEntityListener {

    @PrePersist
    public void setTenant(Object entity) {
        if (entity instanceof TenantAwareEntity tae) {
            if (tae.getTenantId() == null || tae.getTenantId().isBlank()) {
                String tenantId = TenantContext.get();
                if (tenantId == null || tenantId.isBlank()) {
                    // FAIL CLOSED (#21): refuse to persist a tenant-aware row with no tenant, rather
                    // than letting it hit the DB as an opaque NOT NULL violation (or, worse, an
                    // untenanted row on a nullable schema). Names the type so the cause is obvious.
                    throw new IllegalStateException("Cannot persist " + tae.getClass().getSimpleName()
                            + ": no tenant in context. Set the tenant before tenant-aware writes.");
                }
                tae.setTenantId(tenantId);
            }
        }
    }
}
