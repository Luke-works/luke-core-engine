package com.luke.engine.admin;

import com.luke.engine.config.ApiCallerResolver;
import java.util.List;
import java.util.Map;
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

    // Shared with operator/IdP-driven deprovisioning (#38) so the cleanup can't drift.
    private final UserDeprovisioningService deprovisioning;
    private final com.luke.engine.audit.AdminAuditService audit;
    private final ApiCallerResolver callers;

    public AccountController(UserDeprovisioningService deprovisioning,
                            com.luke.engine.audit.AdminAuditService audit, ApiCallerResolver callers) {
        this.deprovisioning = deprovisioning;
        this.audit = audit;
        this.callers = callers;
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
        // tenant scope is null: an account deletion spans every tenant the user belonged to.
        audit.record("account.delete", "account", userId, null, userId, false,
                Map.of("deletedTenants", deletedTenants));
        return ResponseEntity.ok(Map.of("deletedTenants", deletedTenants));
    }

    /* ── auth (Bearer act-as | Basic), mirroring /engine-rest ─────────── */

    private String resolveUserId(String authHeader) {
        // Account deletion may run for a not-yet-provisioned user (idempotent no-op), so a valid
        // Bearer sub is accepted as-is (requireProvisioned=false).
        String userId = callers.resolve(authHeader, false);
        if (userId == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Valid credentials required");
        }
        return userId;
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
