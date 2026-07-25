package com.luke.engine.capability.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.capability.CapabilitySubscription;
import com.luke.engine.capability.capability.CapabilitySubscriptionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.junit.jupiter.api.Test;

/**
 * #51: effectiveCapabilities resolves the tenant's active subscriptions in ONE query.
 * #104 AC-2: a tenant OWNER (tenant-admin) implicitly holds read-write on every subscribed
 * capability (no per-capability grant admin), while an explicit per-user grant still overrides
 * that owner floor.
 */
class CapabilityAccessServiceTest {

    private final CapabilityGrantRepository grants = mock(CapabilityGrantRepository.class);
    private final CapabilitySubscriptionRepository subs = mock(CapabilitySubscriptionRepository.class);
    // Deep stubs so TenantOwnership.isOwner's group-query chain returns 0 (not owner) by default.
    private final IdentityService identity = mock(IdentityService.class, RETURNS_DEEP_STUBS);
    private final CapabilityAccessService service = new CapabilityAccessService(grants, subs, identity);

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

    /** Make (userId, tenantId) a tenant owner via the exact owner-group query TenantOwnership uses. */
    private void makeOwner(String tenantId, String userId) {
        when(identity.createGroupQuery().groupId("owner:" + tenantId).groupMember(userId).count())
                .thenReturn(1L);
    }

    @Test
    void includesOnlyGrantsForActiveSubscriptions_withoutPerGrantQueries() {
        when(grants.findByTenantIdAndUserId("t", "u"))
                .thenReturn(List.of(grant("FORMS", "read-write"), grant("EMAIL", "read")));
        // Only FORMS is active for the tenant.
        when(subs.findByTenantIdAndStatus("t", "ACTIVE")).thenReturn(List.of(sub("FORMS")));

        Map<String, String> eff = service.effectiveCapabilities("t", "u"); // "u" is not an owner

        assertEquals(Map.of("FORMS", "read-write"), eff);
        verify(subs).findByTenantIdAndStatus("t", "ACTIVE");          // one batched query
        verify(subs, never()).findByTenantIdAndCapabilityCode(anyString(), anyString()); // no N+1
    }

    @Test
    void ownerImplicitlyHasReadWriteOnEverySubscribedCapability() {
        makeOwner("t", "owner");
        when(grants.findByTenantIdAndUserId("t", "owner")).thenReturn(List.of()); // no explicit grants
        when(subs.findByTenantIdAndStatus("t", "ACTIVE")).thenReturn(List.of(sub("FORMS"), sub("EMAIL")));

        assertEquals(Map.of("FORMS", "read-write", "EMAIL", "read-write"),
                service.effectiveCapabilities("t", "owner"));

        when(subs.findByTenantIdAndCapabilityCode("t", "FORMS")).thenReturn(Optional.of(sub("FORMS")));
        assertThat(service.effectiveLevel("t", "owner", "FORMS")).isEqualTo("read-write");
        // ...and that read-write floor permits the privileged actions too.
        assertThat(service.permits("t", "owner", "FORMS", CapabilityLevel.Action.PUBLISH)).isTrue();
    }

    @Test
    void explicitGrantOverridesTheOwnerFloor() {
        makeOwner("t", "owner");
        when(subs.findByTenantIdAndStatus("t", "ACTIVE")).thenReturn(List.of(sub("FORMS")));
        when(subs.findByTenantIdAndCapabilityCode("t", "FORMS")).thenReturn(Optional.of(sub("FORMS")));
        when(grants.findByTenantIdAndUserId("t", "owner")).thenReturn(List.of(grant("FORMS", "read")));
        when(grants.findByTenantIdAndUserIdAndCapabilityCode("t", "owner", "FORMS"))
                .thenReturn(Optional.of(grant("FORMS", "read")));

        // The explicit `read` grant wins over the owner's read-write floor (floor, not ceiling).
        assertThat(service.effectiveLevel("t", "owner", "FORMS")).isEqualTo("read");
        assertEquals(Map.of("FORMS", "read"), service.effectiveCapabilities("t", "owner"));
    }

    @Test
    void nonOwnerWithoutGrantHasNoAccess() {
        when(subs.findByTenantIdAndCapabilityCode("t", "FORMS")).thenReturn(Optional.of(sub("FORMS")));
        when(grants.findByTenantIdAndUserIdAndCapabilityCode("t", "u", "FORMS")).thenReturn(Optional.empty());
        assertThat(service.effectiveLevel("t", "u", "FORMS")).isNull();
    }
}
