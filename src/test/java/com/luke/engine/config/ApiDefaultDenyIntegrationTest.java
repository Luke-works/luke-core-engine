package com.luke.engine.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * #20 (AC): with the default-deny baseline enforcing, a brand-new unmapped {@code /api/**} endpoint
 * is denied with 401 when called without credentials — proving defense-in-depth against a future
 * controller shipped without its own auth. Runs against a real embedded server so the filter's
 * {@code /api/*} URL mapping genuinely applies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "luke.auth.api-default-deny=true")
class ApiDefaultDenyIntegrationTest {

    private static final String PROBE = "/api/__default_deny_probe__"; // no controller maps this

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private IdentityService identity;

    @Test
    void newUnmappedApiEndpointReturns401WithoutCredentials() {
        ResponseEntity<String> res = rest.getForEntity(PROBE, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicPathIsNotDeniedByTheBaseline() {
        ResponseEntity<String> res = rest.getForEntity("/api/public/__nope__", String.class);
        // Allow-listed → the baseline lets it through to routing (404), it does NOT 401.
        assertThat(res.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validEngineBasicPassesTheBaseline() {
        String user = "ddprobe-" + UUID.randomUUID();
        User u = identity.newUser(user);
        u.setPassword("pw");
        identity.saveUser(u);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(user, "pw");
            ResponseEntity<String> res = rest.exchange(PROBE, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            // Authenticated → baseline passes it through to routing (404), NOT 401.
            assertThat(res.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            try { identity.deleteUser(user); } catch (RuntimeException ignore) { /* best effort */ }
        }
    }
}
