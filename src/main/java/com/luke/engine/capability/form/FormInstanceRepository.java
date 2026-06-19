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
}
