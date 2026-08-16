package com.luke.engine.billing;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stripe customer mapping keyed by tenantId, with a reverse lookup by Stripe customer id. */
public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, String> {

    /** Resolve a webhook's Stripe customer id back to the tenant that owns it. */
    Optional<BillingCustomer> findByStripeCustomerId(String stripeCustomerId);
}
