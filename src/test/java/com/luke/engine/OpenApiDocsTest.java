package com.luke.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * #35: the OpenAPI 3 spec for the custom {@code /api/**} surface is generated and published at
 * {@code /v3/api-docs}, scoped to {@code /api/**} (not the Camunda {@code /engine-rest} surface), and
 * documents the auth schemes + the internal-key header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void openApiSpecIsPublishedAndScopedToTheApiSurface() {
        ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body)
                .contains("\"openapi\"")
                .contains("luke-core-engine")   // document metadata
                .contains("/api/")              // the custom surface is documented
                .contains("gatewayBearer")      // auth scheme
                .contains("basicAuth")
                .contains("X-Internal-Key");    // internal-key header scheme
        // Scoped: no path is documented under Camunda's own JAX-RS /engine-rest surface. (A path key
        // is JSON `"/engine-rest...`; the description's backtick-quoted mention doesn't match this.)
        assertThat(body).doesNotContain("\"/engine-rest");
    }
}
