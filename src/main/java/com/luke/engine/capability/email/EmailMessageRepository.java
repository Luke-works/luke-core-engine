package com.luke.engine.capability.email;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, String> {

    Optional<EmailMessage> findByIdAndTenantId(String id, String tenantId);

    /**
     * Inbound dedup: has this Postmark message already been received for this tenant?
     *
     * <p>{@code findFirst} rather than a unique lookup on purpose — the unique index added in
     * V23 is skipped on any environment that already held duplicates, so this query must
     * tolerate finding more than one row instead of throwing where the index is absent.
     */
    Optional<EmailMessage> findFirstByTenantIdAndDirectionAndPostmarkMessageId(
            String tenantId, String direction, String postmarkMessageId);

    /* ── retention purge (#53): send logs carry recipient PII, delete past the window ── */

    long countByCreatedAtBefore(LocalDateTime cutoff);

    @Modifying
    @Query("delete from EmailMessage e where e.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Tenant-deletion cascade (#53): a deleted tenant leaves no email PII behind. */
    @Modifying
    @Query("delete from EmailMessage e where e.tenantId = :tenantId")
    int deleteByTenant(@Param("tenantId") String tenantId);

    List<EmailMessage> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<EmailMessage> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);

    // Paged variants (#52) — emails accrue monotonically per tenant, so the list is
    // bounded server-side via the Pageable instead of loading the whole history.
    org.springframework.data.domain.Page<EmailMessage> findByTenantId(
            String tenantId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<EmailMessage> findByTenantIdAndStatus(
            String tenantId, String status, org.springframework.data.domain.Pageable pageable);
}
