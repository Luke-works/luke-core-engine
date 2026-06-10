package com.luke.engine.admin;

import com.luke.engine.config.GatewayJwtAuthenticator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service organization creation. The signed-in user creates a tenant and
 * becomes its owner (tenant-admin) — without being a platform operator.
 *
 * <p>Runs the privileged work itself (engine IdentityService, server-side), so
 * the caller only needs to be authenticated. Unlike most /api endpoints it does
 * NOT require the user to be provisioned first — creating an org is precisely
 * what provisions a brand-new Clerk user.
 *
 * <p>Auth: gateway act-as Bearer (consumer-ui) or Basic (operator/testing).
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private static final Logger log = LoggerFactory.getLogger(OrganizationController.class);
    private static final String TENANT_ADMIN = "tenant-admin";

    @org.springframework.beans.factory.annotation.Value("${luke.capabilities.base-url:http://localhost:8082}")
    private String capabilitiesBaseUrl;

    private final IdentityService identityService;
    private final GatewayJwtAuthenticator gatewayAuth;
    private final org.springframework.web.client.RestTemplate rest = new org.springframework.web.client.RestTemplate();

    public OrganizationController(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth) {
        this.identityService = identityService;
        this.gatewayAuth = gatewayAuth;
    }

    public record CreateOrg(String name, String firstName, String lastName, String email) {}

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                    @RequestBody CreateOrg body) {
        String userId;
        try {
            userId = resolveUserId(authHeader);
        } catch (AuthException e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.title, "message", e.getMessage()));
        }
        if (body == null || body.name() == null || body.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bad Request", "message", "name is required"));
        }
        String name = body.name().trim();

        // Organization names must be unique (case-insensitive, trimmed). CIBSeven's
        // tenantName query is exact/case-sensitive and Postgres LIKE is case-sensitive,
        // so we compare normalized names here. (O(n) over tenants — fine at this scale;
        // revisit with a normalized-name index if org count grows large.)
        String normalized = name.toLowerCase();
        boolean nameTaken = identityService.createTenantQuery().list().stream()
                .anyMatch(t -> t.getName() != null && t.getName().trim().toLowerCase().equals(normalized));
        if (nameTaken) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Conflict",
                    "message", "An organization named '" + name + "' already exists. Please choose a different name."));
        }

        // 1. Ensure the caller exists as an engine user (first-org bootstrap).
        if (identityService.createUserQuery().userId(userId).count() == 0) {
            User u = identityService.newUser(userId);
            u.setFirstName(notBlank(body.firstName()) ? body.firstName() : userId);
            u.setLastName(notBlank(body.lastName()) ? body.lastName() : "");
            if (notBlank(body.email())) u.setEmail(body.email());
            u.setPassword(UUID.randomUUID().toString()); // unusable — WorkOS owns auth
            identityService.saveUser(u);
            log.info("Provisioned engine user '{}' via org creation", userId);
        }

        // 2. Create the tenant. Id = TEN-<3 letters>-<DDMMMYY>; name = the display name.
        String tenantId = uniqueTenantId();
        Tenant tenant = identityService.newTenant(tenantId);
        tenant.setName(name);
        identityService.saveTenant(tenant);

        // 3. Join the creator and make them the owner (tenant-admin).
        identityService.createTenantUserMembership(tenantId, userId);
        if (!isMember(userId, TENANT_ADMIN)) {
            identityService.createMembership(userId, TENANT_ADMIN);
        }
        log.info("User '{}' created org '{}' (tenant {}) as owner", userId, name, tenantId);

        // 4. Give the new org its default capabilities so the owner can use them:
        //    subscribe the tenant, then grant the owner read-write. Effective access
        //    needs BOTH (subscription + per-user grant), so granting is not optional —
        //    without it the owner would have no capabilities and the UI would hide them.
        try {
            // URI template variables so the ':' in "workos:user_…" is encoded exactly
            // once — pre-encoding/concatenation can double-encode and store a key the
            // session never matches, silently dropping the owner's capabilities.
            rest.put(capabilitiesBaseUrl + "/api/tenants/{tenant}/capabilities/FORMS", null, tenantId);
            rest.put(capabilitiesBaseUrl + "/api/tenants/{tenant}/users/{userId}/capabilities/FORMS",
                    Map.of("level", "read-write"), tenantId, userId);
        } catch (Exception e) {
            log.warn("Could not grant owner {} FORMS in tenant {}: {}", userId, tenantId, e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("tenantId", tenantId, "name", name, "role", TENANT_ADMIN));
    }

    /* ── auth (Bearer act-as | Basic); NO provisioning requirement ────── */
    private String resolveUserId(String authHeader) {
        if (authHeader == null) throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid credentials required");
        String lower = authHeader.toLowerCase();
        if (lower.startsWith("bearer ")) {
            String sub = gatewayAuth.authenticate(authHeader.substring(7).trim());
            if (sub == null) throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid or expired token");
            return sub;
        }
        if (lower.startsWith("basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon >= 0 && identityService.checkPassword(decoded.substring(0, colon), decoded.substring(colon + 1))) {
                    return decoded.substring(0, colon);
                }
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid credentials required");
        }
        throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid credentials required");
    }

    private boolean isMember(String userId, String groupId) {
        return identityService.createUserQuery().userId(userId).memberOfGroup(groupId).count() > 0;
    }

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String[] MONTHS = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    /** Tenant id of the form {@code TEN-<3 random letters>-<DDMMMYY>}, regenerated until unique. */
    private String uniqueTenantId() {
        String id = tenantCode();
        while (identityService.createTenantQuery().tenantId(id).count() > 0) {
            id = tenantCode();
        }
        return id;
    }

    /** e.g. {@code TEN-XKQ-08JUN26} — provisioning date in DDMMMYY. */
    private static String tenantCode() {
        java.time.LocalDate d = java.time.LocalDate.now();
        String date = String.format("%02d%s%02d", d.getDayOfMonth(), MONTHS[d.getMonthValue() - 1], d.getYear() % 100);
        StringBuilder sb = new StringBuilder("TEN-");
        for (int i = 0; i < 3; i++) sb.append(LETTERS.charAt(RNG.nextInt(LETTERS.length())));
        return sb.append('-').append(date).toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static class AuthException extends RuntimeException {
        final HttpStatus status;
        final String title;
        AuthException(HttpStatus status, String title, String message) {
            super(message);
            this.status = status;
            this.title = title;
        }
    }
}
