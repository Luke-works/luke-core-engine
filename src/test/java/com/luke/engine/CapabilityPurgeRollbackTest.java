package com.luke.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.access.CapabilityAdminController;
import com.luke.engine.capability.access.CapabilityGrant;
import com.luke.engine.capability.access.CapabilityGrantRepository;
import com.luke.engine.capability.capability.CapabilitySubscriptionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #62: multi-step destructive ops must be atomic. Force the SECOND step of the
 * tenant purge (subscription delete) to fail and assert the FIRST step (grant delete)
 * is rolled back — no half-purged tenant.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:purgerollback;DB_CLOSE_DELAY=-1",
        "luke.auth.gateway.enabled=false"
})
class CapabilityPurgeRollbackTest {

    @Autowired
    private CapabilityAdminController controller;

    @Autowired
    private CapabilityGrantRepository grants;

    @MockitoBean
    private CapabilitySubscriptionRepository subscriptions;

    @Test
    void secondStepFailureRollsBackGrantDeletion() {
        CapabilityGrant g = new CapabilityGrant("tenantX", "userA", "FORMS");
        g.setLevel("READ_WRITE");
        grants.save(g);
        assertEquals(1, grants.findByTenantId("tenantX").size(), "precondition: grant seeded");

        // Make the second step (subscription delete) blow up after grants were deleted.
        when(subscriptions.findByTenantId("tenantX")).thenReturn(List.of());
        doThrow(new RuntimeException("subscription delete failed"))
                .when(subscriptions).deleteAll(any());

        assertThrows(RuntimeException.class, () -> controller.purgeTenant("tenantX"));

        // The grant delete (first step) must have rolled back with the failed transaction.
        assertEquals(1, grants.findByTenantId("tenantX").size(),
                "grant must be restored when the subscription delete fails — purge is atomic (#62)");
    }
}
