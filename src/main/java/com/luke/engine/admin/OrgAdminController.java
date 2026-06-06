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
import org.springframework.web.client.RestTemplate;
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

    /** Roles an org owner may assign (not tenant-admin / camunda-admin). */
    private static final Map<String, String> ROLE_DIM = Map.of(
            "tenant-user", "tenantUser", "process-operator", "processUser", "task-worker", "taskUser");
    private static final Set<String> ASSIGNABLE_ROLES = ROLE_DIM.keySet();

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;
    @Value("${luke.capabilities.base-url:http://localhost:8082}")
    private String capabilitiesBaseUrl;

    private final IdentityService identityService;
    private final GatewayJwtAuthenticator gatewayAuth;
    private final RestTemplate rest = new RestTemplate();

    public OrgAdminController(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth) {
        this.identityService = identityService;
        this.gatewayAuth = gatewayAuth;
    }

    public record NewUser(String id, String firstName, String lastName, String email, String password,
                          String role, String accessLevel) {}
    public record LevelBody(String level) {}
    public record GroupBody(String name) {}

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
        identityService.createTenantUserMembership(ctx.tenant, body.id());
        identityService.createMembership(body.id(), roleGroup(body.role(), body.accessLevel()));
        return Map.of("id", body.id(), "tenant", ctx.tenant);
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
        deleteMembership(userId, role);
        deleteMembership(userId, role + READONLY);
        if ("read-write".equals(body.level())) identityService.createMembership(userId, role);
        else if ("read".equals(body.level())) identityService.createMembership(userId, role + READONLY);
        else if (!"none".equals(body.level())) throw bad("level must be none|read|read-write");
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

    @PutMapping("/users/{userId}/candidate-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addToCandidateGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                    @PathVariable String userId, @PathVariable String groupId) {
        Ctx ctx = requireAdmin(auth, tenant);
        requireTenantMember(userId, ctx.tenant);
        requireTenantGroup(groupId, ctx.tenant);
        identityService.createMembership(userId, groupId);
    }

    @DeleteMapping("/users/{userId}/candidate-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromCandidateGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                         @PathVariable String userId, @PathVariable String groupId) {
        Ctx ctx = requireAdmin(auth, tenant);
        requireTenantGroup(groupId, ctx.tenant);
        deleteMembership(userId, groupId);
    }

    /* ── capabilities (delegated to capability-engine, after authz) ──── */

    @GetMapping("/capabilities")
    public Object catalog(@RequestHeader(value = "Authorization", required = false) String auth,
                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        requireAdmin(auth, tenant);
        return rest.getForObject(capabilitiesBaseUrl + "/api/capabilities", Object.class);
    }

    @GetMapping("/users/{userId}/capabilities")
    public Object userCapabilities(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                   @PathVariable String userId) {
        Ctx ctx = requireAdmin(auth, tenant);
        requireTenantMember(userId, ctx.tenant);
        return rest.getForObject(capabilitiesBaseUrl + "/api/tenants/" + enc(ctx.tenant) + "/users/" + enc(userId) + "/capabilities", Object.class);
    }

    @PutMapping("/users/{userId}/capabilities/{code}")
    public Object setCapability(@RequestHeader(value = "Authorization", required = false) String auth,
                               @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                               @PathVariable String userId, @PathVariable String code, @RequestBody LevelBody body) {
        Ctx ctx = requireAdmin(auth, tenant);
        requireTenantMember(userId, ctx.tenant);
        String url = capabilitiesBaseUrl + "/api/tenants/" + enc(ctx.tenant) + "/users/" + enc(userId) + "/capabilities/" + enc(code);
        if ("none".equals(body.level())) {
            rest.delete(url);
            return Map.of("removed", true);
        }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var req = new org.springframework.http.HttpEntity<>(Map.of("level", body.level()), headers);
        return rest.exchange(url, org.springframework.http.HttpMethod.PUT, req, Object.class).getBody();
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
        boolean tenantAdmin = groups.stream().anyMatch(g -> "tenant-admin".equals(g.getId()) || ("tenant-admin" + READONLY).equals(g.getId()));
        if (!tenantAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires org owner (tenant-admin)");
        }
        return new Ctx(userId, tenant, false);
    }

    private void requireTenantMember(String userId, String tenant) {
        if (identityService.createUserQuery().userId(userId).memberOfTenant(tenant).count() == 0) {
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

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
