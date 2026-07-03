package com.luke.engine.workflow;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped access to {@link WorkflowDefinition} rows (explicit tenant args, mirroring forms). */
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, String> {

    Optional<WorkflowDefinition> findByIdAndTenantId(String id, String tenantId);

    List<WorkflowDefinition> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}
