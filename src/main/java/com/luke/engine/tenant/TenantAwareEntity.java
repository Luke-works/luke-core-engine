package com.luke.engine.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Base class for all tenant-aware custom entities.
 * Extend this to get:
 * - Automatic tenant filtering on all queries (Hibernate @Filter)
 * - Automatic tenant assignment on insert (TenantEntityListener)
 *
 * Usage:
 * <pre>
 * {@literal @}Entity
 * {@literal @}Table(name = "luke_orders")
 * public class Order extends TenantAwareEntity {
 *     {@literal @}Id
 *     {@literal @}GeneratedValue(strategy = GenerationType.UUID)
 *     private String id;
 *     private String customerName;
 *     private Double amount;
 * }
 * </pre>
 *
 * That's it. No need to:
 * - Manually set tenantId on save
 * - Manually filter by tenantId in queries
 * - Add tenantId to repository method signatures
 */
@MappedSuperclass
@EntityListeners(TenantEntityListener.class)
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = String.class))
@Filter(name = "tenantFilter", condition = "tenant_id_ = :tenantId")
public abstract class TenantAwareEntity {

    @Column(name = "tenant_id_", nullable = false)
    private String tenantId;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
