package com.luke.engine.capability.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * Functional test (real HTTP, H2): with the operator credential configured, WRITES
 * to the GLOBAL capability catalog ({@code /api/capabilities}) require it, while
 * reads stay public. Closes the backlog gap where catalog create/upsert/delete had
 * no auth filter at all (the merge's BACKLOG fix).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "DB_URL=jdbc:h2:mem:catalogauth;DB_CLOSE_DELAY=-1",
                "luke.auth.operator.user=op",
                "luke.auth.operator.password=secret"
        })
class CapabilityCatalogAuthTest {

    @Autowired
    private TestRestTemplate rest;

    private HttpEntity<String> body(HttpHeaders h) {
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>("{\"code\":\"TESTCAP\",\"name\":\"Test\"}", h);
    }

    @Test
    void catalogRead_isPublic() {
        // GET /api/capabilities is global metadata — readable without the operator cred.
        assertNotEquals(401, rest.getForEntity("/api/capabilities", String.class).getStatusCode().value());
    }

    @Test
    void catalogWrite_withoutOperator_isUnauthorized() {
        assertEquals(401, rest.postForEntity("/api/capabilities", body(new HttpHeaders()), String.class)
                .getStatusCode().value());
    }

    @Test
    void catalogWrite_withWrongCredential_isUnauthorized() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth("op", "wrong");
        assertEquals(401, rest.postForEntity("/api/capabilities", body(h), String.class)
                .getStatusCode().value());
    }

    @Test
    void catalogWrite_withOperator_passesTheFilter() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth("op", "secret");
        // Passes the operator filter → reaches the controller (created/conflict), NOT 401.
        assertNotEquals(401, rest.exchange("/api/capabilities", HttpMethod.POST, body(h), String.class)
                .getStatusCode().value());
    }
}
