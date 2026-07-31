package com.luke.engine.capability.email;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboundEmailRepository extends JpaRepository<InboundEmail, String> {

    /** Tenant-scoped read — never load content by id alone. */
    Optional<InboundEmail> findByIdAndTenantId(String id, String tenantId);

    List<InboundEmail> findByTenantIdAndBoxIdOrderByReceivedAtDesc(String tenantId, String boxId);

    /* ── retention (#53) ──────────────────────────────────────────────────────
     * This table holds the BODY of mail a third party sent a tenant, which is more sensitive
     * than the send log in luke_email_messages, not less. It shares that table's primary key,
     * so both the retention purge and the tenant-deletion cascade must delete from here too —
     * otherwise purging the envelope leaves the message text behind, orphaned and unreachable
     * by any tenant-scoped query, which is the worst of both worlds. Keyed on receivedAt so
     * this purge stands on its own rather than depending on the other table's rows. */

    long countByReceivedAtBefore(LocalDateTime cutoff);

    @Modifying
    @Query("delete from InboundEmail e where e.receivedAt < :cutoff")
    int deleteReceivedBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("delete from InboundEmail e where e.tenantId = :tenantId")
    int deleteByTenant(@Param("tenantId") String tenantId);
}
