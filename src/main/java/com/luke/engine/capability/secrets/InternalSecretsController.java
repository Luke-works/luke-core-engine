package com.luke.engine.capability.secrets;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-to-server secrets broker for the platform's other services (core-engine,
 * luke-agents, task-engine, …) — the PRIMARY surface, since most secrets are
 * internal. Unlike the tenant-facing {@code /api/secrets}, this one DOES return
 * plaintext, because trusted backends need the actual value to use it.
 *
 * <p>Sits behind the shared-secret {@link com.luke.engine.capability.access.InternalAuthFilter}
 * on {@code /api/internal/**} (callers present {@code X-Internal-Key}); it is NOT
 * gateway/capability guarded and NOT proxied to browsers. The tenant is part of the
 * path; use a well-known id (e.g. {@code PLATFORM}) for platform-global secrets.
 */
@RestController
@RequestMapping("/api/internal/secrets")
public class InternalSecretsController {

    // Audit channel (#60): every internal secret access is logged with tenant+name
    // (NEVER the value) and the request correlation id (MDC), so cross-tenant reads
    // via the shared key are at least detectable. NOTE: this is the detection layer;
    // per-service identity / tenant-scoped keys (so the key can't read ANY tenant)
    // remain a follow-up design item.
    private static final Logger audit = LoggerFactory.getLogger("luke.audit.secrets");

    private final SecretsService secrets;

    public InternalSecretsController(SecretsService secrets) {
        this.secrets = secrets;
    }

    public record PutBody(String value, String managedBy, String description) {}
    public record ResolvedSecret(String tenantId, String name, String value) {}

    /** Create/overwrite a secret. Returns masked metadata (not the value). */
    @PutMapping("/{tenantId}/{name}")
    public SecretsService.SecretView put(@PathVariable String tenantId, @PathVariable String name,
                                         @RequestBody PutBody body) {
        audit.info("internal-secret STORE tenant={} name={}", tenantId, name);
        return secrets.store(tenantId, name, body.value(), body.managedBy(), body.description(), "system");
    }

    /** Resolve a secret's PLAINTEXT for a trusted service. 404 if absent. */
    @GetMapping("/{tenantId}/{name}")
    public ResolvedSecret resolve(@PathVariable String tenantId, @PathVariable String name) {
        audit.info("internal-secret RESOLVE tenant={} name={}", tenantId, name);
        String value = secrets.get(tenantId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such secret: " + name));
        return new ResolvedSecret(tenantId, name, value);
    }

    /** List a tenant's secret names (masked, all classes). No plaintext. */
    @GetMapping("/{tenantId}")
    public List<SecretsService.SecretView> list(@PathVariable String tenantId) {
        return secrets.listAll(tenantId);
    }

    @DeleteMapping("/{tenantId}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String tenantId, @PathVariable String name) {
        audit.info("internal-secret DELETE tenant={} name={}", tenantId, name);
        if (!secrets.delete(tenantId, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such secret: " + name);
        }
    }
}
