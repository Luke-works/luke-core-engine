package com.luke.engine.capability.email;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailRoutingRuleRepository extends JpaRepository<EmailRoutingRule, String> {

    /** Every rule for the tenant, in evaluation order (first match wins). */
    List<EmailRoutingRule> findByTenantIdOrderBySortOrderAscCreatedAtAsc(String tenantId);

    Optional<EmailRoutingRule> findByIdAndTenantId(String id, String tenantId);

    long countByTenantId(String tenantId);

    void deleteByTenantIdAndBoxId(String tenantId, String boxId);
}
