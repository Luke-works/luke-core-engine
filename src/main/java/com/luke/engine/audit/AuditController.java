package com.luke.engine.audit;

import com.luke.engine.config.GatewayJwtAuthenticator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read API for the admin audit trail (#37). Self-authenticating like {@code OrgAdminController}
 * (no filter guards {@code /api/audit}) — Bearer act-as or operator Basic.
 *
 * <ul>
 *   <li>{@code GET /api/audit} — events for the active tenant ({@code X-Tenant-Id}); caller must be a
 *       platform operator OR an owner (tenant-admin) of that tenant. This is the tenant-admin-scoped
 *       view of their own tenant's events.
 *   <li>{@code GET /api/audit/all} — the cross-tenant view; platform operators only.
 * </ul>
 *
 * Both are newest-first and paginated ({@code ?page=&size=}), with an optional {@code ?action=} filter.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    private final AuditEventRepository repository;
    private final IdentityService identityService;
    private final GatewayJwtAuthenticator gatewayAuth;

    public AuditController(AuditEventRepository repository, IdentityService identityService,
                          GatewayJwtAuthenticator gatewayAuth) {
        this.repository = repository;
        this.identityService = identityService;
        this.gatewayAuth = gatewayAuth;
    }

    /** Events for the active tenant. Operator or owner of that tenant. */
    @GetMapping
    public Map<String, Object> tenantEvents(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        String tenantId = requireOperatorOrOwner(auth, tenant);
        Pageable pageable = pageable(page, size);
        Page<AuditEvent> events = (action == null || action.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                : repository.findByTenantIdAndActionOrderByCreatedAtDesc(tenantId, action, pageable);
        return body(events);
    }

    /** Cross-tenant view — platform operators only. */
    @GetMapping("/all")
    public Map<String, Object> allEvents(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        requireOperator(auth);
        Pageable pageable = pageable(page, size);
        Page<AuditEvent> events = (action == null || action.isBlank())
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByActionOrderByCreatedAtDesc(action, pageable);
        return body(events);
    }

    /* ── authorization (mirrors OrgAdminController) ───────────────────── */

    /** Caller must be a platform operator, or an owner (tenant-admin) of the active tenant. Returns the tenantId. */
    private String requireOperatorOrOwner(String authHeader, String tenant) {
        String userId = resolveUserId(authHeader);
        if (isOperator(userId)) {
            if (tenant == null || tenant.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
            }
            return tenant;
        }
        if (tenant == null || tenant.isBlank()
                || identityService.createTenantQuery().tenantId(tenant).userMember(userId).count() == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of tenant '" + tenant + "'");
        }
        if (!com.luke.engine.tenant.TenantOwnership.isOwner(identityService, userId, tenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires org owner (tenant-admin)");
        }
        return tenant;
    }

    private void requireOperator(String authHeader) {
        String userId = resolveUserId(authHeader);
        if (!isOperator(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The cross-tenant audit view requires a platform operator");
        }
    }

    private boolean isOperator(String userId) {
        boolean adminGroup = identityService.createGroupQuery().groupMember(userId).list()
                .stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.getId()));
        boolean parentCluster = identityService.createTenantQuery().userMember(userId).list()
                .stream().anyMatch(t -> parentClusterId.equals(t.getId()));
        return adminGroup || parentCluster;
    }

    private String resolveUserId(String authHeader) {
        if (authHeader != null) {
            String lower = authHeader.toLowerCase();
            if (lower.startsWith("bearer ")) {
                String sub = gatewayAuth.authenticate(authHeader.substring(7).trim());
                if (sub != null && identityService.createUserQuery().userId(sub).count() > 0) {
                    return sub;
                }
            } else if (lower.startsWith("basic ")) {
                try {
                    String dec = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
                    int c = dec.indexOf(':');
                    if (c >= 0 && identityService.checkPassword(dec.substring(0, c), dec.substring(c + 1))) {
                        return dec.substring(0, c);
                    }
                } catch (IllegalArgumentException ignored) {
                    // fall through to 401
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid credentials required");
    }

    /* ── helpers ──────────────────────────────────────────────────────── */

    private Pageable pageable(int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size <= 0 ? DEFAULT_PAGE_SIZE : size), MAX_PAGE_SIZE);
        return PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private Map<String, Object> body(Page<AuditEvent> events) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<AuditEvent> content = events.getContent();
        out.put("content", content);
        out.put("page", events.getNumber());
        out.put("size", events.getSize());
        out.put("totalElements", events.getTotalElements());
        out.put("totalPages", events.getTotalPages());
        return out;
    }
}
