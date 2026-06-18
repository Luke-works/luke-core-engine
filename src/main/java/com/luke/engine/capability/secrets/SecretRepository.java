package com.luke.engine.capability.secrets;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecretRepository extends JpaRepository<Secret, String> {

    Optional<Secret> findByTenantIdAndName(String tenantId, String name);

    boolean existsByTenantIdAndName(String tenantId, String name);

    List<Secret> findByTenantIdAndManagedByOrderByCreatedAtDesc(String tenantId, String managedBy);

    List<Secret> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
