package com.luke.engine.admin;

import com.luke.engine.config.GatewayJwtAuthenticator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service account deletion — the engine side of "delete my account".
 *
 * <p>luke-auth-engine calls this (as the user, via an act-as Bearer token) right
 * before it deletes the WorkOS identity, so the engine user, their memberships,
 * any tenant they solely own, and the matching capability grants/subscriptions
 * all get cleaned up instead of being orphaned.
 *
 * <p>Idempotent: if the caller was never provisioned (no engine user), this is a
 * successful no-op so the WorkOS-side delete can still proceed.
 */
@RestController
@RequestMapping("/api")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final IdentityService identityService;
    private final GatewayJwtAuthenticator gatewayAuth;
    // In-process capability cleanup (was server-to-server DELETE via the proxy).
    private final com.luke.engine.capability.access.CapabilityAdminController capabilityAdmin;

    public AccountController(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth,
                            com.luke.engine.capability.access.CapabilityAdminController capabilityAdmin) {
        this.identityService = identityService;
        this.gatewayAuth = gatewayAuth;
        this.capabilityAdmin = capabilityAdmin;
    }

    @DeleteMapping("/me/account")
    public ResponseEntity<?> deleteAccount(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String userId;
        try {
            userId = resolveUserId(authHeader);
        } catch (AuthException e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.title, "message", e.getMessage()));
        }

        // Nothing provisioned in the engine → nothing to clean up.
        if (identityService.createUserQuery().userId(userId).count() == 0) {
            purgeUserCapabilities(userId);
            return ResponseEntity.ok(Map.of("deletedTenants", List.of()));
        }

        // 1. Tenants the user belongs to, and which of them they solely occupy.
        List<String> tenants = identityService.createTenantQuery().userMember(userId).list()
                .stream().map(Tenant::getId).toList();
        List<String> soleOwned = new ArrayList<>();
        for (String t : tenants) {
            if (identityService.createUserQuery().memberOfTenant(t).count() == 1) {
                soleOwned.add(t);
            }
        }

        // 2. Drop the user's memberships (tenant + group), then the user itself.
        for (String t : tenants) {
            safe(() -> identityService.deleteTenantUserMembership(t, userId));
        }
        for (Group g : identityService.createGroupQuery().groupMember(userId).list()) {
            safe(() -> identityService.deleteMembership(userId, g.getId()));
        }
        safe(() -> identityService.deleteUser(userId));

        // 3. Capability cleanup: the user's grants everywhere, and every sole-owned
        //    tenant (which we now delete entirely).
        purgeUserCapabilities(userId);
        for (String t : soleOwned) {
            safe(() -> identityService.deleteTenant(t));
            purgeTenantCapabilities(t);
        }

        log.info("Deleted account '{}' ({} memberships removed, {} tenants deleted)",
                userId, tenants.size(), soleOwned.size());
        return ResponseEntity.ok(Map.of("deletedTenants", soleOwned));
    }

    /* ── capability-engine cleanup (best-effort; never blocks identity delete) ── */

    private void purgeUserCapabilities(String userId) {
        safe(() -> capabilityAdmin.purgeUser(userId));
    }

    private void purgeTenantCapabilities(String tenantId) {
        safe(() -> capabilityAdmin.purgeTenant(tenantId));
    }

    /* ── auth (Bearer act-as | Basic), mirroring /engine-rest ─────────── */

    private String resolveUserId(String authHeader) {
        if (authHeader == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid credentials required");
        }
        String lower = authHeader.toLowerCase();
        if (lower.startsWith("bearer ")) {
            String userId = gatewayAuth.authenticate(authHeader.substring(7).trim());
            if (userId == null) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid or expired token");
            }
            return userId;
        }
        if (lower.startsWith("basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon >= 0 && identityService.checkPassword(decoded.substring(0, colon), decoded.substring(colon + 1))) {
                    return decoded.substring(0, colon);
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to 401
            }
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid credentials required");
        }
        throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid credentials required");
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Exception e) { log.warn("Cleanup step failed (continuing): {}", e.getMessage()); }
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
