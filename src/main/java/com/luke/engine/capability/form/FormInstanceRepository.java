package com.luke.engine.capability.form;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FormInstanceRepository
        extends JpaRepository<FormInstance, String>, JpaSpecificationExecutor<FormInstance> {

    /** Open instances whose expiry has lapsed — swept to EXPIRED (#54). Bounded per run. */
    List<FormInstance> findByStateInAndExpiresAtBefore(
            Collection<String> states, LocalDateTime cutoff, Limit limit);

    /* ── retention (#53): ANONYMIZE submissions past the window — null the PII payload
     *    (data/prefill/recipient) while keeping the non-PII lifecycle row for metrics/audit.
     *    Idempotent: only matches rows that still hold PII. ── */

    @Query("select count(i) from FormInstance i where i.createdAt < :cutoff "
            + "and (i.data is not null or i.prefill is not null or i.recipient is not null)")
    long countAnonymizableBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("update FormInstance i set i.data = null, i.prefill = null, i.recipient = null "
            + "where i.createdAt < :cutoff "
            + "and (i.data is not null or i.prefill is not null or i.recipient is not null)")
    int anonymizeCreatedBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Tenant-deletion cascade (#53): a deleted tenant leaves no submission PII behind. */
    @Modifying
    @Query("delete from FormInstance i where i.tenantId = :tenantId")
    int deleteByTenant(@Param("tenantId") String tenantId);

    Optional<FormInstance> findByIdAndTenantId(String id, String tenantId);

    Optional<FormInstance> findByTokenAndTenantId(String token, String tenantId);

    /** Public per-recipient resolve (outbound fill surface): the opaque token is the sole handle,
     *  so this is intentionally NOT tenant-scoped — the caller has no tenant context. */
    Optional<FormInstance> findByToken(String token);

    boolean existsByToken(String token);

    List<FormInstance> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<FormInstance> findByTenantIdAndStateOrderByCreatedAtDesc(String tenantId, String state);

    List<FormInstance> findByTenantIdAndDefinitionCodeOrderByCreatedAtDesc(String tenantId, String definitionCode);

    // The paged list (#52) + filter/search/sort (#26) is served via
    // JpaSpecificationExecutor.findAll(Specification, Pageable) — see
    // FormInstanceController.list / FormInstanceSpecs — so the page is bounded and
    // filtered server-side instead of loading the whole tenant set.

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
