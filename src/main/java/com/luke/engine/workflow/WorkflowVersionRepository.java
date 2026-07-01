package com.luke.engine.workflow;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to immutable {@link WorkflowVersion} snapshots, keyed by definition. */
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, String> {

    List<WorkflowVersion> findByDefinitionIdOrderByVersionAsc(String definitionId);

    Optional<WorkflowVersion> findByDefinitionIdAndVersion(String definitionId, int version);

    /** Resolve a deployed process back to its version (by the compiled {@code processId}). */
    Optional<WorkflowVersion> findFirstByProcessId(String processId);
}
