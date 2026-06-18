package com.luke.engine.capability.form;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormDefinitionRepository extends JpaRepository<FormDefinition, String> {

    Optional<FormDefinition> findByIdAndTenantId(String id, String tenantId);

    Optional<FormDefinition> findByTenantIdAndCode(String tenantId, String code);

    boolean existsByTenantIdAndCode(String tenantId, String code);

    // Live (non-trashed) listings
    List<FormDefinition> findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId);

    List<FormDefinition> findByTenantIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId, String status);

    // Trash
    List<FormDefinition> findByTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(String tenantId);
}
