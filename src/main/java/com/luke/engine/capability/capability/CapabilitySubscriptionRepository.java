package com.luke.engine.capability.capability;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapabilitySubscriptionRepository extends JpaRepository<CapabilitySubscription, String> {

    List<CapabilitySubscription> findByTenantId(String tenantId);

    List<CapabilitySubscription> findByTenantIdAndStatus(String tenantId, String status);

    Optional<CapabilitySubscription> findByTenantIdAndCapabilityCode(String tenantId, String capabilityCode);
}
