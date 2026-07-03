package com.luke.engine.capability.signature;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped persistence for {@link SignatureInstance}. */
public interface SignatureInstanceRepository extends JpaRepository<SignatureInstance, String> {

    Optional<SignatureInstance> findByIdAndTenantId(String id, String tenantId);

    List<SignatureInstance> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<SignatureInstance> findByTenantIdAndDefinitionCodeOrderByCreatedAtDesc(String tenantId, String definitionCode);
}
