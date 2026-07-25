package com.luke.engine.admin;

import com.luke.engine.config.ApiCallerResolver;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.finos.fluxnova.bpm.engine.identity.User;
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
    private final UserDeprovisioningService deprovisioning;
    private final com.luke.engine.audit.AdminAuditService audit;
    private final com.luke.engine.config.ApiCallerResolver callers;

    public OnboardingController(IdentityService identityService, UserDeprovisioningService deprovisioning,
                               com.luke.engine.audit.AdminAuditService audit,
                               com.luke.engine.config.ApiCallerResolver callers) {
        this.identityService = identityService;
        this.deprovisioning = deprovisioning;
        this.audit = audit;
        this.callers = callers;
    }

    public record OnboardUserRequest(
            String id, String firstName, String lastName,
            String email, String password, String tenantId,
            String role, String accessLevel) {}

    /**
     * Onboarding payload for a Clerk-authenticated consumer user. Identical to
     * {@link OnboardUserRequest} except there is no password — Clerk owns
     * authentication, so the engine user gets a random unusable one — and the
     * id is derived from the Clerk subject, not supplied directly.
     */
    public record OnboardClerkUserRequest(
            String clerkSub, String firstName, String lastName,
            String email, String tenantId,
            String role, String accessLevel) {}

    /** Namespaces Clerk identities in the engine. MUST match luke-auth-engine's IdentityResolver. */
    private static final String CLERK_PREFIX = "clerk:";

    /**
     * Onboard a Clerk user: derive the engine userId as {@code clerk:<sub>}
     * (the exact id luke-auth-engine asserts at request time), assign a random
     * unusable password, then reuse the standard onboarding flow. Until this
     * runs, a Clerk user can authenticate but the engine returns 403
     * "not provisioned".
     */
    @PostMapping("/onboard-clerk-user")
    public ResponseEntity<?> onboardClerkUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody OnboardClerkUserRequest req) {

        if (!StringUtils.hasText(req.clerkSub())) {
            return badRequest("clerkSub is required");
        }
        String engineUserId = CLERK_PREFIX + req.clerkSub();

        OnboardUserRequest delegate = new OnboardUserRequest(
                engineUserId,
                req.firstName(), req.lastName(), req.email(),
                randomUnusablePassword(),
                req.tenantId(), req.role(), req.accessLevel());

        return onboard(authHeader, delegate);
    }

    /** Long random secret so the CIBSeven password field is satisfied but never matches. */
    private String randomUnusablePassword() {
        byte[] bytes = new byte[48];
        new java.security.SecureRandom().nextBytes(bytes);
        return "clerk-nologin-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Operator-driven deprovisioning by engine userId (SCIM / directory-sync leaver, auth-engine #38). */
    public record DeprovisionUserRequest(String id) {}

    /**
     * Revoke all engine access for a user by id — the operator/IdP counterpart to the user's own
     * {@code DELETE /api/me/account}. luke-auth-engine's WorkOS deprovisioning webhook calls this
     * (operator Basic auth) when a user is removed in the IdP, so their engine membership doesn't
     * outlive their identity. Idempotent: an unknown user is a successful no-op.
     */
    @PostMapping("/deprovision-user")
    public ResponseEntity<?> deprovisionUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody DeprovisionUserRequest req) {

        String caller = authenticate(authHeader);
        if (caller == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Valid credentials required"));
        }
        if (!isPrivileged(caller)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", "Deprovisioning requires an operator (camunda-admin / parent_cluster)"));
        }
        if (req == null || !StringUtils.hasText(req.id())) {
            return badRequest("id is required");
        }

        List<String> deletedTenants = deprovisioning.deprovision(req.id());
        log.info("Operator '{}' deprovisioned user '{}' ({} sole-owned tenants deleted)",
                caller, req.id(), deletedTenants.size());
        audit.record("user.deprovision", "user", req.id(), null, caller, true,
                Map.of("deletedTenants", deletedTenants));
        return ResponseEntity.ok(Map.of("id", req.id(), "deletedTenants", deletedTenants, "deprovisioned", true));
    }

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
        // Normalize an incoming WorkOS role slug to the canonical RoleCatalog id first (#61): the
        // gateway may forward an IdP-assigned role, and WorkOS's built-in `member`/`admin` roles must
        // alias to `tenant-user`/`tenant-admin`. The four mirror slugs map to themselves (identity), so
        // existing callers are unaffected; a genuinely-unknown slug falls through unchanged and hits the
        // "Unknown role" 400 below — fail-closed, exactly as before.
        String canonicalRole = com.luke.engine.config.RoleCatalog.fromWorkosSlug(req.role())
                .map(com.luke.engine.config.RoleCatalog::id)
                .orElse(req.role());
        // Resolve the role to the right access tier (Read-Only uses the -readonly variant).
        String roleGroup = resolveRoleGroup(canonicalRole, req.accessLevel());
        if (identityService.createGroupQuery().groupId(roleGroup).count() == 0) {
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
            if (!isGroupMember(roleGroup, req.id())) {
                identityService.createMembership(req.id(), roleGroup);
            }
            // A tenant-admin (either tier) owns the tenant — record the scoped ownership binding
            // that authorization reads, so operator-provisioned admins are owners of THIS tenant.
            if ("tenant-admin".equals(roleGroup) || "tenant-admin-readonly".equals(roleGroup)) {
                com.luke.engine.tenant.TenantOwnership.grant(identityService, req.id(), req.tenantId());
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

        log.info("Onboarded user '{}' into tenant '{}' as '{}' ({}, created={})",
                req.id(), req.tenantId(), roleGroup, accessLevel(req.accessLevel()), createdUser);
        audit.record("user.onboard", "user", req.id(), req.tenantId(), caller, true,
                Map.of("role", roleGroup, "created", createdUser));
        return ResponseEntity.ok(Map.of(
                "id", req.id(),
                "tenantId", req.tenantId(),
                "role", roleGroup,
                "created", createdUser));
    }

    /** Read-Only resolves to the role's -readonly variant when it exists; otherwise the base role. */
    private String resolveRoleGroup(String role, String accessLevel) {
        if (role != null && "READ_ONLY".equalsIgnoreCase(accessLevel)) {
            String readonly = role + "-readonly";
            if (identityService.createGroupQuery().groupId(readonly).count() > 0) {
                return readonly;
            }
        }
        return role;
    }

    private String accessLevel(String value) {
        return "READ_ONLY".equalsIgnoreCase(value) ? "READ_ONLY" : "READ_WRITE";
    }

    /** Returns the authenticated username, or null if Basic auth is missing/invalid.
     *  Onboarding is operator-only and server-to-server → Basic only (see {@link ApiCallerResolver}). */
    private String authenticate(String authHeader) {
        return callers.basicUsername(authHeader);
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
        // GroupQuery form: UserQuery.userId(u).memberOfGroup(g).count() is broken in CIBSeven
        // (ignores the group filter, returns 1 for any existing user) — see TenantOwnership.
        return identityService.createGroupQuery().groupId(groupId).groupMember(userId).count() > 0;
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
