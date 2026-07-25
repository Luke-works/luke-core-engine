package com.luke.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.config.ApiDefaultDenyFilter.Impl;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * #20: the default-deny baseline. Enforcing mode denies any /api request that is neither
 * allow-listed nor carrying a recognized credential; lenient (dev) mode passes everything through
 * untouched.
 */
class ApiDefaultDenyFilterTest {

    private final IdentityService identity = mock(IdentityService.class);
    private final GatewayJwtAuthenticator gateway = mock(GatewayJwtAuthenticator.class);

    private Impl enforcing() {
        return new Impl(identity, gateway, "op", "oppw", true);
    }

    private Impl lenient() {
        return new Impl(identity, gateway, "op", "oppw", false);
    }

    /** Runs the filter and returns whether the chain proceeded (true) or was denied. */
    private boolean proceeds(Impl filter, MockHttpServletRequest req, MockHttpServletResponse res) throws Exception {
        boolean[] proceeded = {false};
        FilterChain chain = (rq, rs) -> proceeded[0] = true;
        filter.doFilter(req, res, chain);
        return proceeded[0];
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        return req;
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void lenientModePassesEverythingThrough() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(proceeds(lenient(), request("GET", "/api/anything"), res)).isTrue();
        assertThat(res.getStatus()).isEqualTo(200); // untouched
    }

    @Test
    void enforcingDeniesUnauthenticatedRequestWith401() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(proceeds(enforcing(), request("GET", "/api/some-new-controller"), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void enforcingAllowsPreflightPublicInternalAndCatalogRead() throws Exception {
        assertThat(proceeds(enforcing(), request("OPTIONS", "/api/whatever"), new MockHttpServletResponse())).isTrue();
        assertThat(proceeds(enforcing(), request("GET", "/api/public/embed/tok"), new MockHttpServletResponse())).isTrue();
        assertThat(proceeds(enforcing(), request("POST", "/api/internal/process-start"), new MockHttpServletResponse())).isTrue();
        assertThat(proceeds(enforcing(), request("GET", "/api/capabilities"), new MockHttpServletResponse())).isTrue();
        assertThat(proceeds(enforcing(), request("GET", "/api/capabilities/FORMS"), new MockHttpServletResponse())).isTrue();
    }

    @Test
    void enforcingStillDeniesCatalogWritesWithoutCredentials() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(proceeds(enforcing(), request("POST", "/api/capabilities"), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void enforcingAllowsAValidGatewayBearer() throws Exception {
        when(gateway.authenticate(eq("tok"))).thenReturn("workos:user-1");
        MockHttpServletRequest req = request("GET", "/api/my-capabilities");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(proceeds(enforcing(), req, res)).isTrue();
        assertThat(req.getAttribute(Impl.PRINCIPAL_ATTRIBUTE)).isEqualTo("workos:user-1");
    }

    @Test
    void enforcingAllowsTheOperatorBasicCredential() throws Exception {
        MockHttpServletRequest req = request("PUT", "/api/tenants/T-1/capabilities/EMAIL");
        req.addHeader("Authorization", basic("op", "oppw"));
        assertThat(proceeds(enforcing(), req, new MockHttpServletResponse())).isTrue();
    }

    @Test
    void enforcingAllowsAValidEngineBasic() throws Exception {
        when(identity.checkPassword(eq("alice"), eq("pw"))).thenReturn(true);
        MockHttpServletRequest req = request("GET", "/api/me");
        req.addHeader("Authorization", basic("alice", "pw"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(proceeds(enforcing(), req, res)).isTrue();
        assertThat(req.getAttribute(Impl.PRINCIPAL_ATTRIBUTE)).isEqualTo("alice");
    }

    @Test
    void enforcingDeniesAWrongBasicPassword() throws Exception {
        when(identity.checkPassword(eq("alice"), eq("nope"))).thenReturn(false);
        MockHttpServletRequest req = request("GET", "/api/me");
        req.addHeader("Authorization", basic("alice", "nope"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(proceeds(enforcing(), req, res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }
}
