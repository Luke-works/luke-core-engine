package com.luke.engine.capability.access;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapabilityGrantRepository extends JpaRepository<CapabilityGrant, String> {

    Optional<CapabilityGrant> findByTenantIdAndUserIdAndCapabilityCode(String tenantId, String userId, String capabilityCode);

    List<CapabilityGrant> findByTenantIdAndUserId(String tenantId, String userId);

    List<CapabilityGrant> findByTenantId(String tenantId);

    List<CapabilityGrant> findByUserId(String userId);
}
