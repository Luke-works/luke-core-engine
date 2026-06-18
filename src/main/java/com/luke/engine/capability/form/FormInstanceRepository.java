package com.luke.engine.capability.form;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormInstanceRepository extends JpaRepository<FormInstance, String> {

    Optional<FormInstance> findByIdAndTenantId(String id, String tenantId);

    Optional<FormInstance> findByTokenAndTenantId(String token, String tenantId);

    boolean existsByToken(String token);

    List<FormInstance> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<FormInstance> findByTenantIdAndStateOrderByCreatedAtDesc(String tenantId, String state);

    List<FormInstance> findByTenantIdAndDefinitionCodeOrderByCreatedAtDesc(String tenantId, String definitionCode);
}
