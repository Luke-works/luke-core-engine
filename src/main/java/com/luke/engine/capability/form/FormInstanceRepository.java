package com.luke.engine.capability.form;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Per-definition rollup for the cockpit summary (#26): total, received-submission
     *  count and last-activity, computed server-side so the UI no longer reduces the
     *  whole (possibly truncated) client array. One row per definitionCode. */
    interface DefinitionSummary {
        String getCode();
        long getTotal();
        long getSubs();
        LocalDateTime getLastAt();
    }

    @Query("SELECT i.definitionCode AS code, COUNT(i) AS total, "
            + "SUM(CASE WHEN i.state IN :subStates THEN 1L ELSE 0L END) AS subs, "
            + "MAX(COALESCE(i.submittedAt, i.createdAt)) AS lastAt "
            + "FROM FormInstance i WHERE i.tenantId = :tenantId GROUP BY i.definitionCode")
    List<DefinitionSummary> summarizeByDefinition(
            @Param("tenantId") String tenantId, @Param("subStates") Collection<String> subStates);
}
