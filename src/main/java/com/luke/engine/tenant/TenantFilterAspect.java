package com.luke.engine.tenant;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that automatically enables the Hibernate tenant filter
 * before any Spring Data JPA repository method executes.
 * This ensures all queries on TenantAwareEntity subclasses are
 * automatically scoped to the current tenant.
 */
@Aspect
@Component
public class TenantFilterAspect {

    private final EntityManager entityManager;

    public TenantFilterAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Before("execution(* org.springframework.data.jpa.repository.JpaRepository+.*(..))")
    public void enableTenantFilter() {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return;
        }
        Session session = entityManager.unwrap(Session.class);
        // Only enable the filter if it's actually registered. The "tenantFilter"
        // @FilterDef lives on TenantAwareEntity, which NO mapped entity extends
        // today (RegisteredTopic and the merged-in capability entities are plain
        // entities), so Hibernate never registers it. Without this guard the aspect
        // throws UnknownFilterException on EVERY tenant-scoped repository call —
        // e.g. /api/my-capabilities after the capability merge (X-Tenant-Id sets
        // TenantContext, then a capability repo runs). When an entity does adopt
        // TenantAwareEntity, the filter registers and this activates automatically.
        if (session.getSessionFactory().getDefinedFilterNames().contains("tenantFilter")) {
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
        }
    }
}
