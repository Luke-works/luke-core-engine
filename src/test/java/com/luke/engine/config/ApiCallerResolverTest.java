package com.luke.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.UserQuery;
import org.junit.jupiter.api.Test;

/**
 * #20 AC-2: the single credential-parsing seam the /api controllers now share. Covers the raw
 * primitives (Bearer sub, Basic username) and the {@code resolve(header, requireProvisioned)}
 * convenience — including the "valid Bearer but no engine user yet" distinction that separates the
 * provisioning-required callers (org-admin, audit) from the first-org / delete-account ones.
 */
class ApiCallerResolverTest {

    private final IdentityService identity = mock(IdentityService.class);
    private final GatewayJwtAuthenticator gateway = mock(GatewayJwtAuthenticator.class);
    private final ApiCallerResolver resolver = new ApiCallerResolver(identity, gateway);

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    /** Stub identityService.createUserQuery().userId(x).count() → count. */
    private void stubUserExists(long count) {
        UserQuery q = mock(UserQuery.class);
        when(identity.createUserQuery()).thenReturn(q);
        when(q.userId(anyString())).thenReturn(q);
        when(q.count()).thenReturn(count);
    }

    @Test
    void bearerSubReturnsTheVerifiedSubOrNull() {
        when(gateway.authenticate(eq("tok"))).thenReturn("workos:u1");
        assertThat(resolver.bearerSub("Bearer tok")).isEqualTo("workos:u1");
        assertThat(resolver.bearerSub("bearer tok")).isEqualTo("workos:u1"); // case-insensitive scheme
        assertThat(resolver.bearerSub(basic("a", "b"))).isNull();            // not a Bearer
        assertThat(resolver.bearerSub(null)).isNull();
    }

    @Test
    void basicUsernameChecksThePasswordOrReturnsNull() {
        when(identity.checkPassword(eq("alice"), eq("pw"))).thenReturn(true);
        assertThat(resolver.basicUsername(basic("alice", "pw"))).isEqualTo("alice");
        assertThat(resolver.basicUsername(basic("alice", "wrong"))).isNull();
        assertThat(resolver.basicUsername("Bearer tok")).isNull(); // not Basic
        assertThat(resolver.basicUsername("Basic !!not-base64!!")).isNull();
    }

    @Test
    void resolveWithoutProvisioningAcceptsAnyValidTokenSub() {
        when(gateway.authenticate(eq("tok"))).thenReturn("workos:new-user");
        assertThat(resolver.resolve("Bearer tok", false)).isEqualTo("workos:new-user");
    }

    @Test
    void resolveWithProvisioningRejectsAnUnknownBearerSub() {
        when(gateway.authenticate(eq("tok"))).thenReturn("workos:new-user");
        stubUserExists(0); // no engine user yet
        assertThat(resolver.resolve("Bearer tok", true)).isNull();
    }

    @Test
    void resolveWithProvisioningAcceptsAKnownBearerSub() {
        when(gateway.authenticate(eq("tok"))).thenReturn("workos:existing");
        stubUserExists(1);
        assertThat(resolver.resolve("Bearer tok", true)).isEqualTo("workos:existing");
    }

    @Test
    void resolveFallsBackToBasic() {
        when(identity.checkPassword(eq("bob"), eq("pw"))).thenReturn(true);
        assertThat(resolver.resolve(basic("bob", "pw"), true)).isEqualTo("bob");  // Basic ignores requireProvisioned
        assertThat(resolver.resolve(basic("bob", "pw"), false)).isEqualTo("bob");
        assertThat(resolver.resolve(null, false)).isNull();
    }
}
