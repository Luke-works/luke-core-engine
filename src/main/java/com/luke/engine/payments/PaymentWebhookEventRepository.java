package com.luke.engine.payments;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, String> {

    /** Stripe retries for up to three days; a week of history is ample for dedupe. */
    @Modifying
    @Transactional
    @Query("delete from PaymentWebhookEvent e where e.receivedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /** A purged tenant's account ids must not linger in the dedupe log. */
    @Modifying
    @Transactional
    @Query("delete from PaymentWebhookEvent e where e.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") String accountId);
}
