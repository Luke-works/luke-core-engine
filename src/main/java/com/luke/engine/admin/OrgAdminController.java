package com.luke.engine.admin;

import com.luke.engine.config.GatewayJwtAuthenticator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-scoped admin API for org owners. Every call is authorized — the caller
 * must be a tenant-admin of the active tenant (X-Tenant-Id) or a platform
 * operator — and every operation is constrained to that tenant. This is what
 * lets an org owner self-serve user/role/access management WITHOUT the global
 * Camunda user/group admin a platform operator has.
 *
 * <p>Camunda roles/groups are managed here directly; capability grants are
 * delegated to capability-engine (server-to-server) after authorization.
 */
@RestController
@RequestMapping("/api/org")
public class OrgAdminController {

    private static final Logger log = LoggerFactory.getLogger(OrgAdminController.class);
    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";
    private static final String ROLE_TYPE = "ROLE";
    private static final String ORGANIZATIONAL_TYPE = "ORGANIZATIONAL";
    private static final String READONLY = "-readonly";

    private static final String TENANT_ADMIN = "tenant-admin";

    /** Roles an org owner may assign. Includes {@code tenant-admin} so an owner can
     *  promote co-owners — guarded so the last owner can never be removed. */
    private static final Map<String, String> ROLE_DIM = Map.of(
            "tenant-admin", "tenantAdmin",
            "tenant-user", "tenantUser", "process-operator", "processUser", "task-worker", "taskUser");
    private static final Set<String> ASSIGNABLE_ROLES = ROLE_DIM.keySet();

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    private final IdentityService identityService;
    private final GatewayJwtAuthenticator gatewayAuth;
    // In-process capability data store (was server-to-server HTTP via the proxy + operator cred).
    private final com.luke.engine.capability.capability.CapabilityController capabilities;
    private final com.luke.engine.capability.capability.SubscriptionController subscriptions;
    private final com.luke.engine.capability.access.CapabilityGrantController grants;

    public OrgAdminController(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth,
                             com.luke.engine.capability.capability.CapabilityController capabilities,
                             com.luke.engine.capability.capability.SubscriptionController subscriptions,
                             com.luke.engine.capability.access.CapabilityGrantController grants) {
        this.identityService = identityService;
        this.gatewayAuth = gatewayAuth;
        this.capabilities = capabilities;
        this.subscriptions = subscriptions;
        this.grants = grants;
    }

    public record NewUser(String id, String firstName, String lastName, String email, String password,
                          String role, String accessLevel) {}
    public record LevelBody(String level) {}
    public record GroupBody(String name) {}
    public record UserProfile(String firstName, String lastName) {}

    /* ── users in the org, with roles + candidate groups ─────────────── */

    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        Ctx ctx = requireAdmin(auth, tenant);
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : identityService.createUserQuery().memberOfTenant(ctx.tenant).list()) {
            List<Group> groups = identityService.createGroupQuery().groupMember(u.getId()).list();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("firstName", u.getFirstName());
            row.put("lastName", u.getLastName());
            row.put("email", u.getEmail());
            row.put("roles", rolesOf(groups));
            row.put("candidateGroups", candidateGroupsOf(groups, ctx.tenant));
            // Platform (admin/support) accounts are camunda-admin members — flagged so
            // the UI can separate them from real end users (the admin is auto-added to
            // every tenant for support access).
            row.put("platform", groups.stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.getId())));
            out.add(row);
        }
        return out;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createUser(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                          @RequestBody NewUser body) {
        Ctx ctx = requireAdmin(auth, tenant);
        if (body.id() == null || body.id().isBlank()) throw bad("id is required");
        if (!ASSIGNABLE_ROLES.contains(body.role())) throw bad("role must be one of " + ASSIGNABLE_ROLES);
        if (identityService.createUserQuery().userId(body.id()).count() == 0) {
            User u = identityService.newUser(body.id());
            u.setFirstName(body.firstName());
            u.setLastName(body.lastName());
            u.setEmail(body.email());
            u.setPassword(body.password() != null ? body.password() : UUID.randomUUID().toString());
            identityService.saveUser(u);
        }
        // Idempotent: role groups are GLOBAL, so a user already holding this role in another tenant
        // is already in the group — an unguarded createMembership would throw a duplicate-key error
        // (500) when adding an existing user to a second org. Guard both memberships.
        if (identityService.createTenantQuery().tenantId(ctx.tenant).userMember(body.id()).count() == 0) {
            identityService.createTenantUserMembership(ctx.tenant, body.id());
        }
        String rg = roleGroup(body.role(), body.accessLevel());
        if (identityService.createGroupQuery().groupId(rg).groupMember(body.id()).count() == 0) {
            identityService.createMembership(body.id(), rg);
        }
        // A tenant-admin is an owner OF this tenant — record the scoped binding authz reads.
        if (TENANT_ADMIN.equals(body.role())) {
            com.luke.engine.tenant.TenantOwnership.grant(identityService, body.id(), ctx.tenant);
        }
        return Map.of("id", body.id(), "tenant", ctx.tenant);
    }

    /**
     * Update a member's display name (first/last) in the engine user store — what the
     * UI resolves created_by/updated_by/audit-actor ids to. Owner (tenant-admin) or
     * operator only, and the target must be a member of the active tenant.
     */
    @PutMapping("/users/{userId}/profile")
    public Map<String, Object> updateProfile(@RequestHeader(value = "Authorization", required = false) String auth,
                                             @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                             @PathVariable String userId, @RequestBody UserProfile body) {
        Ctx ctx = requireAdmin(auth, tenant);
        requireTenantMember(userId, ctx.tenant);
        User u = identityService.createUserQuery().userId(userId).singleResult();
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user: " + userId);
        if (body.firstName() != null) u.setFirstName(body.firstName().trim());
        if (body.lastName() != null) u.setLastName(body.lastName().trim());
        identityService.saveUser(u);
        return Map.of("id", userId,
                "firstName", u.getFirstName() != null ? u.getFirstName() : "",
                "lastName", u.getLastName() != null ? u.getLastName() : "");
    }

    /* ── roles (Camunda) ─────────────────────────────────────────────── */

    @PutMapping("/users/{userId}/roles/{role}")
    public Map<String, Object> setRole(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                       @PathVariable String userId, @PathVariable String role,
                                       @RequestBody LevelBody body) {
        Ctx ctx = requireAdmin(auth, tenant);
        requireTenantMember(userId, ctx.tenant);
        if (!ASSIGNABLE_ROLES.contains(role)) throw bad("role must be one of " + ASSIGNABLE_ROLES);
        // Don't let an org lose its last owner: removing tenant-admin from the only
        // remaining owner (of THIS tenant) would leave nobody able to administer it.
        if (TENANT_ADMIN.equals(role) && "none".equals(body.level())
                && com.luke.engine.tenant.TenantOwnership.isOwner(identityService, userId, ctx.tenant)
                && !hasOtherOwner(userId, ctx.tenant)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot remove the last org owner. Assign another owner first.");
        }
        deleteMembership(userId, role);
        deleteMembership(userId, role + READONLY);
        if ("read-write".equals(body.level())) identityService.createMembership(userId, role);
        else if ("read".equals(body.level())) identityService.createMembership(userId, role + READONLY);
        else if (!"none".equals(body.level())) throw bad("level must be none|read|read-write");
        // Keep the scoped ownership binding (what authorization actually reads) in sync with the
        // tenant-admin role: any tenant-admin tier owns this tenant; 'none' revokes ownership.
        if (TENANT_ADMIN.equals(role)) {
            if ("none".equals(body.level())) {
                com.luke.engine.tenant.TenantOwnership.revoke(identityService, userId, ctx.tenant);
            } else {
                com.luke.engine.tenant.TenantOwnership.grant(identityService, userId, ctx.tenant);
            }
        }
        return Map.of("userId", userId, "role", role, "level", body.level());
    }

    /* ── candidate groups (ABAC), namespaced to the tenant ───────────── */

    @GetMapping("/candidate-groups")
    public List<Map<String, String>> candidateGroups(@RequestHeader(value = "Authorization", required = false) String auth,
                                                     @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        Ctx ctx = requireAdmin(auth, tenant);
        String prefix = ctx.tenant + ":";
        return identityService.createGroupQuery().groupType(ORGANIZATIONAL_TYPE).list().stream()
                .filter(g -> g.getId().startsWith(prefix))
                .map(g -> Map.of("id", g.getId(), "name", g.getName() == null ? g.getId() : g.getName()))
                .toList();
    }

    @PostMapping("/candidate-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createCandidateGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                                    @RequestBody GroupBody body) {
        Ctx ctx = requireAdmin(auth, tenant);
        if (body.name() == null || body.name().isBlank()) throw bad("name is required");
        String id = ctx.tenant + ":" + body.name().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (identityService.createGroupQuery().groupId(id).count() == 0) {
            Group g = identityService.newGroup(id);
            g.setName(body.name().trim());
            g.setType(ORGANIZATIONAL_TYPE);
            identityService.saveGroup(g);
        }
        return Map.of("id", id, "name", body.name().trim());
    }

    // Managing a candidate group's MEMBERS is allowed for a tenant owner OR a manager of that specific
    // candidate group (delegated via /candidate-groups/{groupId}/managers below).
    @PutMapping("/users/{userId}/candidate-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addToCandidateGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                    @PathVariable String userId, @PathVariable String groupId) {
        Ctx ctx = requireCandidateGroupAccess(auth, tenant, groupId);
        requireTenantGroup(groupId, ctx.tenant);
        requireTenantMember(userId, ctx.tenant);
        // Idempotent: a duplicate createMembership throws (500). Only add if not already a member.
        if (identityService.createGroupQuery().groupId(groupId).groupMember(userId).count() == 0) {
            identityService.createMembership(userId, groupId);
        }
    }

    @DeleteMapping("/users/{userId}/candidate-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromCandidateGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                         @PathVariable String userId, @PathVariable String groupId) {
        Ctx ctx = requireCandidateGroupAccess(auth, tenant, groupId);
        requireTenantGroup(groupId, ctx.tenant);
        deleteMembership(userId, groupId);
    }

    /* ── candidate-group managers (delegated management; appoint/remove is OWNER-only) ── */

    /** Users who may manage the membership of {@code groupId}. Visible to a tenant owner or a manager
     *  of that group. */
    @GetMapping("/candidate-groups/{groupId}/managers")
    public List<Map<String, Object>> candidateGroupManagers(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @PathVariable String groupId) {
        Ctx ctx = requireCandidateGroupAccess(auth, tenant, groupId);
        requireTenantGroup(groupId, ctx.tenant);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : com.luke.engine.tenant.CandidateGroupOwnership.managerIds(identityService, groupId)) {
            User u = identityService.createUserQuery().userId(id).singleResult();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("firstName", u != null ? u.getFirstName() : null);
            row.put("lastName", u != null ? u.getLastName() : null);
            out.add(row);
        }
        return out;
    }

    /** Appoint {@code userId} as a manager of candidate group {@code groupId}. OWNER-only: a manager
     *  can never grow the set of managers (the model you chose). */
    @PutMapping("/candidate-groups/{groupId}/managers/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addCandidateGroupManager(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                         @PathVariable String groupId, @PathVariable String userId) {
        Ctx ctx = requireAdmin(auth, tenant); // owner-only
        requireTenantGroup(groupId, ctx.tenant);
        requireCandidateGroupExists(groupId);
        requireTenantMember(userId, ctx.tenant);
        com.luke.engine.tenant.CandidateGroupOwnership.grant(identityService, userId, groupId);
    }

    /** Remove {@code userId} as a manager of candidate group {@code groupId}. OWNER-only. */
    @DeleteMapping("/candidate-groups/{groupId}/managers/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCandidateGroupManager(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                            @PathVariable String groupId, @PathVariable String userId) {
        Ctx ctx = requireAdmin(auth, tenant); // owner-only
        requireTenantGroup(groupId, ctx.tenant);
        com.luke.engine.tenant.CandidateGroupOwnership.revoke(identityService, userId, groupId);
    }

    /* ── capabilities (delegated to capability-engine, after authz) ──── */

    @GetMapping("/capabilities")
    public Object catalog(@RequestHeader(value = "Authorization", required = false) String auth,
                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        requireAdmin(auth, tenant);
        return capabilities.list(null);
    }

    @GetMapping("/users/{userId}/capabilities")
    public Object userCapabilities(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                   @PathVariable String userId) {
        Ctx ctx = requireAdmin(auth, tenant);
        requireTenantMember(userId, ctx.tenant);
        return grants.listGrants(ctx.tenant, userId);
    }

    @PutMapping("/users/{userId}/capabilities/{code}")
    public Object setCapability(@RequestHeader(value = "Authorization", required = false) String auth,
                               @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                               @PathVariable String userId, @PathVariable String code, @RequestBody LevelBody body) {
        Ctx ctx = requireAdmin(auth, tenant);
        requireTenantMember(userId, ctx.tenant);
        if ("none".equals(body.level())) {
            grants.revoke(ctx.tenant, userId, code);
            return Map.of("removed", true);
        }
        // EMAIL is a company-sending capability: a personal/free mailbox account can't
        // verify a business sender, so it must not be granted to one (the UI also hides
        // the option, but enforce it here too so the API can't be bypassed).
        if ("EMAIL".equalsIgnoreCase(code)) {
            User target = identityService.createUserQuery().userId(userId).singleResult();
            String email = target != null ? target.getEmail() : null;
            if (PersonalEmail.isPersonal(email)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Email can't be granted to a personal email account (" + email + "). Use a company email.");
            }
        }
        // Granting a user requires the tenant to be subscribed first (two-layer model):
        // an owner enabling a capability for a user implies the org has it, mirroring
        // onboarding's subscribe+grant. In-process now (was server-to-server). The
        // capability beans throw ResponseStatusException (404/409) which Spring surfaces
        // with the right status — no downstream-error translation needed.
        subscriptions.enable(ctx.tenant, code);
        return grants.setGrant(ctx.tenant, userId, code, ctx.userId,
                new com.luke.engine.capability.access.CapabilityGrantController.GrantBody(body.level()));
    }

    /* ── authorization + helpers ─────────────────────────────────────── */

    private record Ctx(String userId, String tenant, boolean operator) {}

    /** Caller must be a platform operator, or a tenant-admin member of the active tenant. */
    private Ctx requireAdmin(String authHeader, String tenant) {
        String userId = resolveUserId(authHeader);
        List<Group> groups = identityService.createGroupQuery().groupMember(userId).list();
        List<String> tenants = identityService.createTenantQuery().userMember(userId).list()
                .stream().map(t -> t.getId()).toList();
        boolean operator = groups.stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.getId())) || tenants.contains(parentClusterId);

        if (operator) {
            if (tenant == null || tenant.isBlank()) throw bad("X-Tenant-Id is required");
            return new Ctx(userId, tenant, true);
        }
        if (tenant == null || tenant.isBlank() || !tenants.contains(tenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of tenant '" + tenant + "'");
        }
        // Scoped: owner OF THIS tenant (not the global tenant-admin role, which would let an
        // admin of any org administer this one). See TenantOwnership.
        if (!com.luke.engine.tenant.TenantOwnership.isOwner(identityService, userId, tenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires org owner (tenant-admin)");
        }
        return new Ctx(userId, tenant, false);
    }

    /** Caller may manage the MEMBERS of candidate group {@code groupId}: a platform operator, a
     *  tenant owner, or an appointed manager of that specific candidate group. (requireTenantGroup,
     *  called by the endpoint right after, still pins {@code groupId} to the active tenant — so a
     *  manager of another tenant's group can't cross over.) */
    private Ctx requireCandidateGroupAccess(String authHeader, String tenant, String groupId) {
        String userId = resolveUserId(authHeader);
        List<Group> groups = identityService.createGroupQuery().groupMember(userId).list();
        List<String> tenants = identityService.createTenantQuery().userMember(userId).list()
                .stream().map(t -> t.getId()).toList();
        boolean operator = groups.stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.getId())) || tenants.contains(parentClusterId);
        if (operator) {
            if (tenant == null || tenant.isBlank()) throw bad("X-Tenant-Id is required");
            return new Ctx(userId, tenant, true);
        }
        if (tenant == null || tenant.isBlank() || !tenants.contains(tenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of tenant '" + tenant + "'");
        }
        boolean owner = com.luke.engine.tenant.TenantOwnership.isOwner(identityService, userId, tenant);
        boolean manager = com.luke.engine.tenant.CandidateGroupOwnership.isManager(identityService, userId, groupId);
        if (!owner && !manager) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires org owner or candidate-group manager");
        }
        return new Ctx(userId, tenant, false);
    }

    /** The candidate group must exist before managers can be appointed to it. */
    private void requireCandidateGroupExists(String groupId) {
        if (identityService.createGroupQuery().groupId(groupId).count() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown candidate group: " + groupId);
        }
    }

    /** Is there another owner of {@code tenant} besides {@code excludeUserId}? */
    private boolean hasOtherOwner(String excludeUserId, String tenant) {
        long owners = com.luke.engine.tenant.TenantOwnership.ownerCount(identityService, tenant);
        boolean selfIsOwner = com.luke.engine.tenant.TenantOwnership.isOwner(identityService, excludeUserId, tenant);
        return owners - (selfIsOwner ? 1 : 0) > 0;
    }

    private void requireTenantMember(String userId, String tenant) {
        // TenantQuery form: UserQuery.userId(u).memberOfTenant(t).count() is broken in CIBSeven the
        // same way userId+memberOfGroup is — it ignores the filter and returns 1 for any user, so the
        // old form never rejected a non-member (an owner could act on users outside their org). See
        // TenantOwnership / TenantMembershipQueryTest.
        if (identityService.createTenantQuery().tenantId(tenant).userMember(userId).count() == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User '" + userId + "' is not in this org");
        }
    }

    private void requireTenantGroup(String groupId, String tenant) {
        if (!groupId.startsWith(tenant + ":")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group '" + groupId + "' is not in this org");
        }
    }

    private Map<String, String> rolesOf(List<Group> groups) {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("tenantAdmin", "none");
        roles.put("tenantUser", "none");
        roles.put("processUser", "none");
        roles.put("taskUser", "none");
        for (Group g : groups) {
            if (!ROLE_TYPE.equals(g.getType())) continue;
            boolean ro = g.getId().endsWith(READONLY);
            String base = ro ? g.getId().substring(0, g.getId().length() - READONLY.length()) : g.getId();
            String dim = ROLE_DIM.get(base);
            if (dim != null && rank(ro ? "read" : "read-write") > rank(roles.get(dim))) {
                roles.put(dim, ro ? "read" : "read-write");
            }
        }
        return roles;
    }

    private List<String> candidateGroupsOf(List<Group> groups, String tenant) {
        String prefix = tenant + ":";
        return groups.stream().filter(g -> ORGANIZATIONAL_TYPE.equals(g.getType()) && g.getId().startsWith(prefix))
                .map(Group::getId).toList();
    }

    private static int rank(String level) {
        return switch (level) { case "read-write" -> 2; case "read" -> 1; default -> 0; };
    }

    private String roleGroup(String role, String accessLevel) {
        return "READ_ONLY".equalsIgnoreCase(accessLevel) ? role + READONLY : role;
    }

    private void deleteMembership(String userId, String groupId) {
        try { identityService.deleteMembership(userId, groupId); } catch (Exception ignored) {}
    }

    private String resolveUserId(String authHeader) {
        if (authHeader != null) {
            String lower = authHeader.toLowerCase();
            if (lower.startsWith("bearer ")) {
                String sub = gatewayAuth.authenticate(authHeader.substring(7).trim());
                if (sub != null && identityService.createUserQuery().userId(sub).count() > 0) return sub;
            } else if (lower.startsWith("basic ")) {
                try {
                    String dec = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
                    int c = dec.indexOf(':');
                    if (c >= 0 && identityService.checkPassword(dec.substring(0, c), dec.substring(c + 1))) return dec.substring(0, c);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid credentials required");
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
