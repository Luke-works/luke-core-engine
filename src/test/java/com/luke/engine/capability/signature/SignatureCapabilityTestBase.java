package com.luke.engine.capability.signature;

import com.luke.engine.capability.access.CapabilityGrantController;
import com.luke.engine.capability.capability.SubscriptionController;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared setup for the signature MockMvc integration tests now that the capability
 * runs inside core. In the standalone engine the routes were unguarded (a dev filter
 * injected identity); in core they sit behind the {@code CapabilityAccessInterceptor}
 * exactly like FORMS/EMAIL, so a request with X-Tenant-Id/X-User-Id is admitted only
 * when that tenant is subscribed AND the user is granted read-write on SIGNATURES.
 *
 * <p>Tests call {@link #grantSignatures} in a {@code @BeforeEach} for every
 * tenant/user pair they exercise. Granting both sides of a tenant-isolation check is
 * intentional: the interceptor then lets the request through and the 404 comes from
 * the data layer (the other tenant simply can't see the row), which is what the test
 * means to assert. Mirrors {@code OrganizationController.grantCapability}.
 */
abstract class SignatureCapabilityTestBase {

    @Autowired
    SubscriptionController subscriptions;
    @Autowired
    CapabilityGrantController grants;

    /** Subscribe the tenant to SIGNATURES and grant the user read-write (idempotent). */
    protected void grantSignatures(String tenantId, String userId) {
        subscriptions.enable(tenantId, "SIGNATURES");
        grants.setGrant(tenantId, userId, "SIGNATURES", userId,
                new CapabilityGrantController.GrantBody("read-write"));
    }
}
