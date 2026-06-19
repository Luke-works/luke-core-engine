package com.luke.engine.capability.form;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormInstanceRepository extends JpaRepository<FormInstance, String> {

    /** Open instances whose expiry has lapsed — swept to EXPIRED (#54). Bounded per run. */
    List<FormInstance> findByStateInAndExpiresAtBefore(
            Collection<String> states, LocalDateTime cutoff, Limit limit);

    Optional<FormInstance> findByIdAndTenantId(String id, String tenantId);

    Optional<FormInstance> findByTokenAndTenantId(String token, String tenantId);

    boolean existsByToken(String token);

    List<FormInstance> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<FormInstance> findByTenantIdAndStateOrderByCreatedAtDesc(String tenantId, String state);

    List<FormInstance> findByTenantIdAndDefinitionCodeOrderByCreatedAtDesc(String tenantId, String definitionCode);

    // Paged variants (#52) — sort is supplied via the Pageable so the page is bounded
    // server-side instead of loading the whole (monotonically growing) tenant set.
    org.springframework.data.domain.Page<FormInstance> findByTenantId(
            String tenantId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<FormInstance> findByTenantIdAndState(
            String tenantId, String state, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<FormInstance> findByTenantIdAndDefinitionCode(
            String tenantId, String definitionCode, org.springframework.data.domain.Pageable pageable);
}
