package com.luke.engine.capability.signature;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped persistence for {@link SignatureDefinition}. All reads filter by tenantId. */
public interface SignatureDefinitionRepository extends JpaRepository<SignatureDefinition, String> {

    Optional<SignatureDefinition> findByIdAndTenantId(String id, String tenantId);

    Optional<SignatureDefinition> findByTenantIdAndCode(String tenantId, String code);

    /** Live (non-deleted) definitions, most-recently-updated first. */
    List<SignatureDefinition> findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDescCreatedAtDesc(String tenantId);

    /** All definitions incl. soft-deleted (for the "deleted" filter). */
    List<SignatureDefinition> findByTenantIdOrderByUpdatedAtDescCreatedAtDesc(String tenantId);

    boolean existsByTenantIdAndCode(String tenantId, String code);
}
