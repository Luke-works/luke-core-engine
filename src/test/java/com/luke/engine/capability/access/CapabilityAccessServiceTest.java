package com.luke.engine.capability.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.capability.CapabilitySubscription;
import com.luke.engine.capability.capability.CapabilitySubscriptionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #51: effectiveCapabilities resolves the tenant's active subscriptions in ONE query
 *  (no per-grant tenantHasCapability N+1). */
class CapabilityAccessServiceTest {

    private CapabilityGrant grant(String code, String level) {
        CapabilityGrant g = new CapabilityGrant();
        g.setCapabilityCode(code);
        g.setLevel(level);
        return g;
    }

    private CapabilitySubscription sub(String code) {
        CapabilitySubscription s = new CapabilitySubscription();
        s.setCapabilityCode(code);
        s.setStatus("ACTIVE");
        return s;
    }

    @Test
    void includesOnlyGrantsForActiveSubscriptions_withoutPerGrantQueries() {
        CapabilityGrantRepository grants = mock(CapabilityGrantRepository.class);
        CapabilitySubscriptionRepository subs = mock(CapabilitySubscriptionRepository.class);
        when(grants.findByTenantIdAndUserId("t", "u"))
                .thenReturn(List.of(grant("FORMS", "read-write"), grant("EMAIL", "read")));
        // Only FORMS is active for the tenant.
        when(subs.findByTenantIdAndStatus("t", "ACTIVE")).thenReturn(List.of(sub("FORMS")));

        Map<String, String> eff = new CapabilityAccessService(grants, subs).effectiveCapabilities("t", "u");

        assertEquals(Map.of("FORMS", "read-write"), eff);
        verify(subs).findByTenantIdAndStatus("t", "ACTIVE");          // one batched query
        verify(subs, never()).findByTenantIdAndCapabilityCode(anyString(), anyString()); // no N+1
    }
}
