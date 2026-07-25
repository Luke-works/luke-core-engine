package com.luke.engine.capability.email;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, String> {

    /** The active (latest) challenge for a tenant in a given status. */
    Optional<EmailVerification> findFirstByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);

    /** The most recent challenge for a tenant, whatever its status. */
    Optional<EmailVerification> findFirstByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** All still-PENDING challenges for a tenant (to supersede on a new request). */
    List<EmailVerification> findByTenantIdAndStatus(String tenantId, String status);

    /* ── retention (#53): terminal OTP challenges (VERIFIED/EXPIRED/FAILED) keep the org
     *    email/domain long after the proof is consumed — redact that PII past the window,
     *    keeping the non-PII audit row. Idempotent via the redacted-marker guard. ── */

    @Query("select count(v) from EmailVerification v where v.createdAt < :cutoff "
            + "and v.status in :terminal and v.email <> :redacted")
    long countRedactableBefore(@Param("cutoff") LocalDateTime cutoff,
                               @Param("terminal") Collection<String> terminal,
                               @Param("redacted") String redacted);

    @Modifying
    @Query("update EmailVerification v set v.email = :redacted, v.domain = :redacted, v.orgName = :redacted "
            + "where v.createdAt < :cutoff and v.status in :terminal and v.email <> :redacted")
    int redactTerminalBefore(@Param("cutoff") LocalDateTime cutoff,
                             @Param("terminal") Collection<String> terminal,
                             @Param("redacted") String redacted);

    /** Tenant-deletion cascade (#53): drop a deleted tenant's OTP challenge rows (org email PII). */
    @Modifying
    @Query("delete from EmailVerification v where v.tenantId = :tenantId")
    int deleteByTenant(@Param("tenantId") String tenantId);
}
