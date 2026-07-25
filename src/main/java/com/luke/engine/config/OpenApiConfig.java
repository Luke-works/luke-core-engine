package com.luke.engine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes a machine-readable OpenAPI 3 document for the custom {@code /api/**} surface at
 * {@code /v3/api-docs} (#35). springdoc auto-derives each operation from the Spring
 * {@code @RestController} mappings — including the required headers, which are picked up from the
 * {@code @RequestHeader} parameters ({@code X-Tenant-Id}, {@code Authorization}) — so consumers
 * (consumer-ui, auth-engine) get a real contract instead of hand-reading controllers. Scoped to
 * {@code /api/**} via {@code springdoc.paths-to-match} (the JAX-RS {@code /engine-rest} surface is
 * Camunda's own and documented upstream). This bean sets the document metadata + the auth schemes;
 * the JSON is exportable for the api-collection / consumer contract tests.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI coreEngineOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("luke-core-engine")
                        .version("v1")
                        .description("""
                                The FluxNova (Camunda-7) engine's CUSTOM API surface (`/api/**`) — \
                                capability data (forms, email, signatures, phone, documents), org/user \
                                admin, the audit trail, and the public embed/sign/webhook routes. \
                                (Camunda's own REST API lives at `/engine-rest/**` and is not part of \
                                this document.)

                                Authentication by route class:
                                - User-facing capability + admin routes: a gateway act-as **Bearer** \
                                token (verified `sub`) OR HTTP **Basic** (operator / engine user), \
                                with the active tenant in the **`X-Tenant-Id`** header.
                                - `/api/internal/**`: server-to-server shared secret in the \
                                **`X-Internal-Key`** header (fail-closed).
                                - `/api/public/**`: unauthenticated by design — the signed token / \
                                HMAC in the path IS the auth.
                                - Operator-only admin routes (`/api/tenants/**`, `/api/capabilities` \
                                writes) require the operator Basic credential.

                                Under the `prod` profile every non-allow-listed `/api` request must \
                                authenticate (default-deny baseline, #20). Errors share one shape: \
                                `{error, message, status, correlationId}`. See the repo `README.md` \
                                and `docs/runbooks/` for the full integration contract.""")
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes("gatewayBearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Gateway act-as token minted by luke-auth-engine; its verified `sub` is the acting user."))
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("basic")
                                .description("Operator credential or engine username/password (admin + self-service routes)."))
                        .addSecuritySchemes("internalKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Internal-Key")
                                .description("Shared secret for the server-to-server `/api/internal/**` routes.")));
    }
}
