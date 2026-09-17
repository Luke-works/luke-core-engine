package com.luke.engine.payments;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FormPaymentRepository extends JpaRepository<FormPayment, String> {

    Optional<FormPayment> findByInstanceId(String instanceId);

    Optional<FormPayment> findByInstanceIdAndTenantId(String instanceId, String tenantId);

    Optional<FormPayment> findByIntentId(String intentId);

    /** Settlement takes the row lock: a webhook, a payer's sync and the reconciler may race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from FormPayment p where p.id = :id")
    Optional<FormPayment> lockById(@Param("id") String id);

    /**
     * Charges untouched since {@code cutoff} that still need Stripe ({@link FormPayment#isUnsettled}):
     * open ones, plus FAILED / UNRESOLVED ones that carry an intent — it may still take money, so it is
     * cancelled, or its success recorded, once Stripe can be read. Oldest first, so a row that keeps
     * failing (its timestamp is moved on each time) rotates behind the rest rather than starving them.
     */
    @Query("select p from FormPayment p where p.updatedAt < :cutoff and (p.status in :open"
            + " or (p.status in ('FAILED', 'UNRESOLVED') and p.intentId is not null)) order by p.updatedAt asc, p.id asc")
    List<FormPayment> findStale(@Param("open") Collection<String> open, @Param("cutoff") LocalDateTime cutoff,
                                Pageable page);

    /** A tenant's charges that are not settled yet (see {@link #findStale} for the FAILED case). */
    @Query("select p from FormPayment p where p.tenantId = :tenantId and (p.status in :open"
            + " or (p.status in ('FAILED', 'UNRESOLVED') and p.intentId is not null))")
    List<FormPayment> findUnsettledByTenant(@Param("tenantId") String tenantId, @Param("open") Collection<String> open);

    @Modifying
    @Transactional
    @Query("delete from FormPayment p where p.tenantId = :tenantId")
    int deleteByTenant(@Param("tenantId") String tenantId);
}
