package com.luke.engine.admin;

import com.luke.engine.config.GatewayJwtAuthenticator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.Tenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's <em>effective</em> Camunda access, rolled up into the 3 platform
 * roles — what the auth layer reads to tell the UI what a user can do.
 *
 * <p>The engine has finer-grained role groups (tenant-admin, tenant-user,
 * task-worker, process-operator, deployer; each in a read-write + {@code -readonly}
 * tier). This endpoint buckets them into three dimensions —
 * {@code tenantUser / processUser / taskUser} — each resolved to a level
 * (none / read / read-write), and surfaces the user's candidate groups (the
 * ABAC attribute) and tenants. Camunda still <em>enforces</em> the underlying
 * authorizations itself; this is a read-only projection for display.
 *
 * <p>Authenticates with the same dual scheme as {@code /engine-rest}: a gateway
 * act-as Bearer token (from luke-auth-engine) or HTTP Basic.
 */
@RestController
@RequestMapping("/api")
public class PermissionsController {

    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";
    private static final String ROLE_TYPE = "ROLE";
    private static final String ORGANIZATIONAL_TYPE = "ORGANIZATIONAL";
    private static final String READONLY_SUFFIX = "-readonly";

    private static final String NONE = "none";
    private static final String READ = "read";
    private static final String READ_WRITE = "read-write";

    /** Engine role group → the platform dimension it rolls up into. */
    private static final Map<String, String> ROLE_DIMENSION = Map.of(
            "tenant-admin", "tenantUser",
            "tenant-user", "tenantUser",
            "process-operator", "processUser",
            "deployer", "processUser",
            "task-worker", "taskUser");

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    private final IdentityService identityService;
    private final GatewayJwtAuthenticator gatewayAuth;

    public PermissionsController(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth) {
        this.identityService = identityService;
        this.gatewayAuth = gatewayAuth;
    }

    @GetMapping("/me/permissions")
    public ResponseEntity<?> permissions(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String userId;
        try {
            userId = resolveUserId(authHeader);
        } catch (AuthException e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.title, "message", e.getMessage()));
        }

        List<Group> groups = identityService.createGroupQuery().groupMember(userId).list();
        List<Tenant> tenantList = identityService.createTenantQuery().userMember(userId).list();
        List<String> tenants = tenantList.stream().map(Tenant::getId).toList();
        // id → display name, so the UI can label the tenant switcher with the org's name
        // (the id itself is an opaque code). Falls back to the id if a tenant has no name.
        Map<String, String> tenantNames = new LinkedHashMap<>();
        for (Tenant t : tenantList) {
            tenantNames.put(t.getId(), t.getName() != null && !t.getName().isBlank() ? t.getName() : t.getId());
        }

        boolean operator = groups.stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.getId()))
                || tenants.contains(parentClusterId);

        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("tenantUser", NONE);
        roles.put("processUser", NONE);
        roles.put("taskUser", NONE);
        List<String> candidateGroups = new ArrayList<>();
        List<Map<String, String>> rawGroups = new ArrayList<>();
        boolean tenantAdmin = false;

        for (Group g : groups) {
            rawGroups.add(Map.of("id", g.getId(), "type", String.valueOf(g.getType())));
            if (ROLE_TYPE.equals(g.getType())) {
                boolean readOnly = g.getId().endsWith(READONLY_SUFFIX);
                String base = readOnly ? g.getId().substring(0, g.getId().length() - READONLY_SUFFIX.length()) : g.getId();
                if ("tenant-admin".equals(base)) tenantAdmin = true; // org owner / tenant admin
                String dim = ROLE_DIMENSION.get(base);
                if (dim != null) {
                    roles.put(dim, maxLevel(roles.get(dim), readOnly ? READ : READ_WRITE));
                }
            } else if (ORGANIZATIONAL_TYPE.equals(g.getType())) {
                candidateGroups.add(g.getId());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("operator", operator);
        body.put("tenantAdmin", tenantAdmin);
        body.put("tenants", tenants);
        body.put("tenantNames", tenantNames);
        body.put("roles", roles);
        body.put("candidateGroups", candidateGroups);
        body.put("groups", rawGroups);
        return ResponseEntity.ok(body);
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
            if (identityService.createUserQuery().userId(userId).count() == 0) {
                throw new AuthException(HttpStatus.FORBIDDEN, "Forbidden",
                        "User '" + userId + "' is authenticated but not yet onboarded to the engine");
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

    private static String maxLevel(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String level) {
        return switch (level) {
            case READ_WRITE -> 2;
            case READ -> 1;
            default -> 0;
        };
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
