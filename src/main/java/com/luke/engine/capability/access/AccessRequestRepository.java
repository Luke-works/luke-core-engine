package com.luke.engine.capability.access;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Access requests, always read tenant-filtered (a tenant can never see another
 * tenant's requests). Mirrors {@link CapabilityGrantRepository}'s tenant-scoped
 * lookups.
 */
public interface AccessRequestRepository extends JpaRepository<AccessRequest, String> {

    Optional<AccessRequest> findByIdAndTenantId(String id, String tenantId);

    List<AccessRequest> findByTenantIdAndStatusOrderByRequestedAtDesc(String tenantId, String status);

    List<AccessRequest> findByTenantIdAndUserIdOrderByRequestedAtDesc(String tenantId, String userId);

    boolean existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(
            String tenantId, String userId, String capabilityCode, String status);
}
