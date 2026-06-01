package com.luke.engine.admin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only user onboarding: creates a user, joins them to a tenant, and
 * assigns a role group — in one call, instead of three separate engine-rest
 * round-trips from the UI.
 *
 * <p>Served under {@code /api} (outside the engine-rest auth/tenant filters), so
 * it authenticates the caller itself via Basic auth and requires the caller to
 * be a privileged operator (camunda-admin or parent_cluster member).
 *
 * <p>Effectively atomic: validates everything first, then creates; if a later
 * membership step fails it compensates by deleting a just-created user.
 * Idempotent: re-running a partially-completed onboard fills in only what's
 * missing rather than failing on "user exists".
 */
@RestController
@RequestMapping("/api/admin")
public class OnboardingController {

    private static final Logger log = LoggerFactory.getLogger(OnboardingController.class);
    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    private final IdentityService identityService;

    public OnboardingController(IdentityService identityService) {
        this.identityService = identityService;
    }

    public record OnboardUserRequest(
            String id, String firstName, String lastName,
            String email, String password, String tenantId, String role) {}

    @PostMapping("/onboard-user")
    public ResponseEntity<?> onboard(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody OnboardUserRequest req) {

        // ── Authenticate + authorize the caller ───────────────────────
        String caller = authenticate(authHeader);
        if (caller == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Valid credentials required"));
        }
        if (!isPrivileged(caller)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", "Onboarding requires an operator (camunda-admin / parent_cluster)"));
        }

        // ── Validate ──────────────────────────────────────────────────
        String missing = firstBlank(req);
        if (missing != null) {
            return badRequest(missing + " is required");
        }
        if (identityService.createTenantQuery().tenantId(req.tenantId()).count() == 0) {
            return badRequest("Unknown tenant '" + req.tenantId() + "'");
        }
        if (identityService.createGroupQuery().groupId(req.role()).count() == 0) {
            return badRequest("Unknown role '" + req.role() + "'");
        }

        // ── Create (with compensation) ────────────────────────────────
        boolean createdUser = false;
        if (identityService.createUserQuery().userId(req.id()).count() == 0) {
            User user = identityService.newUser(req.id());
            user.setFirstName(req.firstName());
            user.setLastName(req.lastName());
            user.setEmail(req.email());
            user.setPassword(req.password());
            identityService.saveUser(user);
            createdUser = true;
        }

        try {
            if (!isTenantMember(req.tenantId(), req.id())) {
                identityService.createTenantUserMembership(req.tenantId(), req.id());
            }
            if (!isGroupMember(req.role(), req.id())) {
                identityService.createMembership(req.id(), req.role());
            }
        } catch (Exception e) {
            if (createdUser) {
                try {
                    identityService.deleteUser(req.id());
                } catch (Exception cleanup) {
                    log.error("Failed to compensate (delete) user '{}' after onboarding error", req.id(), cleanup);
                }
            }
            log.error("Onboarding failed for user '{}'", req.id(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Onboarding failed", "message", String.valueOf(e.getMessage())));
        }

        log.info("Onboarded user '{}' into tenant '{}' as '{}' (created={})", req.id(), req.tenantId(), req.role(), createdUser);
        return ResponseEntity.ok(Map.of(
                "id", req.id(),
                "tenantId", req.tenantId(),
                "role", req.role(),
                "created", createdUser));
    }

    /** Returns the authenticated username, or null if Basic auth is missing/invalid. */
    private String authenticate(String authHeader) {
        if (authHeader == null || !authHeader.toLowerCase().startsWith("basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String username = decoded.substring(0, colon);
            String password = decoded.substring(colon + 1);
            return identityService.checkPassword(username, password) ? username : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isPrivileged(String username) {
        boolean adminGroup = identityService.createGroupQuery()
                .groupMember(username).list().stream()
                .map(Group::getId).anyMatch(CAMUNDA_ADMIN_GROUP::equals);
        boolean parentCluster = identityService.createTenantQuery()
                .userMember(username).list().stream()
                .map(Tenant::getId).anyMatch(parentClusterId::equals);
        return adminGroup || parentCluster;
    }

    private boolean isTenantMember(String tenantId, String userId) {
        return identityService.createTenantQuery().tenantId(tenantId).userMember(userId).count() > 0;
    }

    private boolean isGroupMember(String groupId, String userId) {
        return identityService.createUserQuery().userId(userId).memberOfGroup(groupId).count() > 0;
    }

    private String firstBlank(OnboardUserRequest r) {
        if (!StringUtils.hasText(r.id())) return "id";
        if (!StringUtils.hasText(r.firstName())) return "firstName";
        if (!StringUtils.hasText(r.lastName())) return "lastName";
        if (!StringUtils.hasText(r.email())) return "email";
        if (!StringUtils.hasText(r.password())) return "password";
        if (!StringUtils.hasText(r.tenantId())) return "tenantId";
        if (!StringUtils.hasText(r.role())) return "role";
        return null;
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", "Bad Request", "message", message));
    }
}
