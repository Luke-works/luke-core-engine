package com.luke.engine.payments;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Connected accounts keyed by tenantId. One Stripe account may serve several tenants. */
public interface PaymentAccountRepository extends JpaRepository<PaymentAccount, String> {

    List<PaymentAccount> findByStripeAccountId(String stripeAccountId);

    @Modifying
    @Transactional
    @Query("delete from PaymentAccount a where a.id = :tenantId")
    int deleteByTenant(@Param("tenantId") String tenantId);
}
