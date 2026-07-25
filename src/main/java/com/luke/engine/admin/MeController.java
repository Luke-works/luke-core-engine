package com.luke.engine.admin;

import com.luke.engine.config.ApiCallerResolver;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the <em>current</em> authenticated user's identity context — their
 * groups (with type), tenants, and whether they're a platform operator.
 *
 * <p>The UI uses this to drive role-based route/nav gating. It exists because a
 * regular tenant user can't call {@code GET /engine-rest/group} (no Group READ
 * authorization); here we authenticate the caller and read <em>their own</em>
 * memberships server-side, so no broad authorization is required.
 */
@RestController
@RequestMapping("/api")
public class MeController {

    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    private final IdentityService identityService;
    private final ApiCallerResolver callers;

    public MeController(IdentityService identityService, ApiCallerResolver callers) {
        this.identityService = identityService;
        this.callers = callers;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String userId = callers.basicUsername(authHeader); // /api/me is Basic-only (server-to-server)
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Valid credentials required"));
        }

        List<Map<String, String>> groups = identityService.createGroupQuery().groupMember(userId).list()
                .stream()
                .map(g -> Map.of("id", g.getId(), "type", String.valueOf(g.getType())))
                .toList();

        List<String> tenants = identityService.createTenantQuery().userMember(userId).list()
                .stream().map(Tenant::getId).toList();

        boolean operator = groups.stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.get("id")))
                || tenants.contains(parentClusterId);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "operator", operator,
                "groups", groups,
                "tenants", tenants));
    }
}
