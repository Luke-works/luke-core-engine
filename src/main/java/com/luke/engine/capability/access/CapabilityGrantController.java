package com.luke.engine.capability.access;

import com.luke.engine.capability.capability.CapabilityRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capability access grants.
 *
 *   GET    /api/my-capabilities                                  → caller's effective {code: level} (X-Tenant-Id, X-User-Id)
 *   GET    /api/tenants/{t}/users/{u}/capabilities               → a user's grants (admin view)
 *   PUT    /api/tenants/{t}/users/{u}/capabilities/{code}        → set level (read | read-write)
 *   DELETE /api/tenants/{t}/users/{u}/capabilities/{code}        → revoke
 *
 * <p>Granting requires the tenant to be subscribed to the capability (two-layer
 * model). {@code my-capabilities} is what the auth layer reads per session.
 */
@RestController
@RequestMapping("/api")
public class CapabilityGrantController {

    private final CapabilityGrantRepository grants;
    private final CapabilityAccessService access;
    private final CapabilityRepository capabilities;

    public CapabilityGrantController(CapabilityGrantRepository grants,
                                     CapabilityAccessService access,
                                     CapabilityRepository capabilities) {
        this.grants = grants;
        this.access = access;
        this.capabilities = capabilities;
    }

    public record GrantBody(String level) {}

    /** Caller's effective capabilities + levels — the per-session read for the auth layer / UI. */
    @GetMapping("/my-capabilities")
    public Map<String, String> myCapabilities(
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(name = "X-User-Id", required = false) String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            return Map.of();
        }
        return access.effectiveCapabilities(tenantId, userId);
    }

    @GetMapping("/tenants/{tenantId}/users/{userId}/capabilities")
    public List<CapabilityGrant> listGrants(@PathVariable String tenantId, @PathVariable String userId) {
        return grants.findByTenantIdAndUserId(tenantId, userId);
    }

    @PutMapping("/tenants/{tenantId}/users/{userId}/capabilities/{code}")
    public CapabilityGrant setGrant(@PathVariable String tenantId, @PathVariable String userId,
                                    @PathVariable String code,
                                    @RequestHeader(value = "X-User-Id", required = false) String actor,
                                    @RequestBody GrantBody body) {
        if (!CapabilityLevel.isValid(body.level())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "level must be 'read' or 'read-write'");
        }
        if (capabilities.findByCode(code).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown capability: " + code);
        }
        if (!access.tenantHasCapability(tenantId, code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tenant " + tenantId + " is not subscribed to " + code + " — subscribe it before granting users.");
        }
        CapabilityGrant grant = grants.findByTenantIdAndUserIdAndCapabilityCode(tenantId, userId, code)
                .orElseGet(() -> new CapabilityGrant(tenantId, userId, code));
        grant.setLevel(body.level());
        grant.setGrantedBy(actor);
        return grants.save(grant);
    }

    @DeleteMapping("/tenants/{tenantId}/users/{userId}/capabilities/{code}")
    public ResponseEntity<Void> revoke(@PathVariable String tenantId, @PathVariable String userId,
                                       @PathVariable String code) {
        grants.findByTenantIdAndUserIdAndCapabilityCode(tenantId, userId, code)
                .ifPresent(grants::delete);
        return ResponseEntity.noContent().build();
    }
}
