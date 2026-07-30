package com.luke.engine.branding;

import org.springframework.data.jpa.repository.JpaRepository;

/** Plan rows keyed by tenantId. A missing row means {@link TenantPlan#PLAN_FREE}. */
public interface TenantPlanRepository extends JpaRepository<TenantPlan, String> {
}
