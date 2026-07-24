package com.luke.engine.admin;

import com.luke.engine.config.GatewayJwtAuthenticator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
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

    private final IdentityService identityService;
    private final GatewayJwtAuthenticator gatewayAuth;
    // Shared with operator/IdP-driven deprovisioning (#38) so the cleanup can't drift.
    private final UserDeprovisioningService deprovisioning;

    public AccountController(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth,
                            UserDeprovisioningService deprovisioning) {
        this.identityService = identityService;
        this.gatewayAuth = gatewayAuth;
        this.deprovisioning = deprovisioning;
    }

    @DeleteMapping("/me/account")
    public ResponseEntity<?> deleteAccount(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String userId;
        try {
            userId = resolveUserId(authHeader);
        } catch (AuthException e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.title, "message", e.getMessage()));
        }
        // Same cleanup as operator/IdP-driven deprovisioning — the caller here is the user itself.
        List<String> deletedTenants = deprovisioning.deprovision(userId);
        return ResponseEntity.ok(Map.of("deletedTenants", deletedTenants));
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
