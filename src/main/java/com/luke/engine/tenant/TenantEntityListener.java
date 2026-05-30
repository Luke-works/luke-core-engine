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
                if (tenantId != null) {
                    tae.setTenantId(tenantId);
                }
            }
        }
    }
}
