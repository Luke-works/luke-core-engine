package com.luke.engine.capability.access;

import com.luke.engine.capability.capability.Capability;
import com.luke.engine.capability.capability.CapabilityRepository;
import com.luke.engine.tenant.UserDirectory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Access-request / approval workflow. A member requests capability access they
 * lack ({@code read} | {@code read-write}); an org owner approves — which
 * actually grants via {@link CapabilityGrantController#setGrant} — or denies.
 *
 * <p>Member routes act as the gateway-asserted caller (X-User-Id) within the
 * active tenant (X-Tenant-Id). Org routes additionally require the caller to be
 * a tenant-admin of that tenant — mirroring {@code OrgAdminController.requireAdmin}.
 *
 * <p>These are org-level (not capability-gated): wired into {@link GatewayAuthFilter}
 * alongside {@code /api/my-capabilities} + {@code /api/org/**}, NOT the
 * {@link AccessWebConfig} capability interceptor.
 */
@RestController
@RequestMapping("/api")
public class AccessRequestController {

    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    private final AccessRequestRepository requests;
    private final CapabilityGrantRepository grants;
    private final CapabilityGrantController grantController;
    private final CapabilityAccessService access;
    private final CapabilityRepository capabilities;
    private final UserDirectory userDirectory;
    private final IdentityService identityService;

    public AccessRequestController(AccessRequestRepository requests,
                                   CapabilityGrantRepository grants,
                                   CapabilityGrantController grantController,
                                   CapabilityAccessService access,
                                   CapabilityRepository capabilities,
                                   UserDirectory userDirectory,
                                   IdentityService identityService) {
        this.requests = requests;
        this.grants = grants;
        this.grantController = grantController;
        this.access = access;
        this.capabilities = capabilities;
        this.userDirectory = userDirectory;
        this.identityService = identityService;
    }

    /* ── request / response bodies ──────────────────────────── */
    public record CreateRequest(String capabilityCode, String level, String note) {}
    public record ApproveBody(String level) {}
    public record DenyBody(String note) {}

    /* ── member endpoints (act as X-User-Id) ────────────────── */

    /** Create a PENDING request for the caller. */
    @PostMapping("/access-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public AccessRequest create(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader("X-User-Id") String userId,
                                @RequestBody CreateRequest body) {
        requireTenant(tenantId);
        requireUser(userId);
        String code = body.capabilityCode();
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capabilityCode is required");
        }
        if (!CapabilityLevel.isValid(body.level())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "level must be 'read' or 'read-write'");
        }
        // Capability must exist AND the tenant must be ACTIVE-subscribed — otherwise
        // it's not something this org can grant at all.
        if (capabilities.findByCode(code).isEmpty() || !access.tenantHasCapability(tenantId, code)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    code + " is not available for your org");
        }
        // Already have access at or above the requested level → nothing to request.
        boolean needWrite = CapabilityLevel.READ_WRITE.equals(body.level());
        String current = grants.findByTenantIdAndUserIdAndCapabilityCode(tenantId, userId, code)
                .map(CapabilityGrant::getLevel).orElse(null);
        if (CapabilityLevel.satisfies(current, needWrite)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "you already have this access");
        }
        // One open request per (tenant, user, capability).
        if (requests.existsByTenantIdAndUserIdAndCapabilityCodeAndStatus(
                tenantId, userId, code, AccessRequest.PENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request already pending");
        }
        AccessRequest req = new AccessRequest(tenantId, userId, code, body.level());
        req.setNote(trimToNull(body.note()));
        return withNames(requests.save(req));
    }

    /** The caller's own requests, any status, newest first. */
    @GetMapping("/my-access-requests")
    public List<AccessRequest> myRequests(@RequestHeader("X-Tenant-Id") String tenantId,
                                          @RequestHeader("X-User-Id") String userId) {
        requireTenant(tenantId);
        requireUser(userId);
        return withNames(requests.findByTenantIdAndUserIdOrderByRequestedAtDesc(tenantId, userId));
    }

    /** Caller cancels their OWN pending request. */
    @PostMapping("/access-requests/{id}/cancel")
    public AccessRequest cancel(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader("X-User-Id") String userId,
                                @PathVariable String id) {
        requireTenant(tenantId);
        requireUser(userId);
        AccessRequest req = load(tenantId, id);
        if (!userId.equals(req.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your request");
        }
        if (!AccessRequest.PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending request can be cancelled");
        }
        req.setStatus(AccessRequest.CANCELLED);
        return withNames(requests.save(req));
    }

    /* ── org endpoints (require tenant-admin) ───────────────── */

    /** The tenant's requests in {@code status} (default PENDING), newest first. */
    @GetMapping("/org/access-requests")
    public List<AccessRequest> orgRequests(@RequestHeader("X-Tenant-Id") String tenantId,
                                           @RequestHeader("X-User-Id") String userId,
                                           @RequestParam(defaultValue = "PENDING") String status) {
        requireAdmin(userId, tenantId);
        return withNames(requests.findByTenantIdAndStatusOrderByRequestedAtDesc(tenantId, status));
    }

    /**
     * Approve a request: mark APPROVED and actually grant access via the existing
     * grant path (the same two-layer subscribe-then-grant the org admin uses), at
     * the requested level unless {@code level} overrides it.
     */
    @PostMapping("/org/access-requests/{id}/approve")
    public AccessRequest approve(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader("X-User-Id") String userId,
                                 @PathVariable String id,
                                 @RequestBody(required = false) ApproveBody body) {
        requireAdmin(userId, tenantId);
        AccessRequest req = load(tenantId, id);
        if (!AccessRequest.PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending request can be approved");
        }
        String level = (body != null && body.level() != null && !body.level().isBlank())
                ? body.level() : req.getRequestedLevel();
        if (!CapabilityLevel.isValid(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "level must be 'read' or 'read-write'");
        }
        // Actually grant — same in-process grant data layer the OrgAdmin tools use.
        grantController.setGrant(tenantId, req.getUserId(), req.getCapabilityCode(), userId,
                new CapabilityGrantController.GrantBody(level));
        req.setStatus(AccessRequest.APPROVED);
        req.setRequestedLevel(level);
        req.setDecidedBy(userId);
        req.setDecidedAt(LocalDateTime.now());
        return withNames(requests.save(req));
    }

    /** Deny a request, with an optional decision note. No grant is made. */
    @PostMapping("/org/access-requests/{id}/deny")
    public AccessRequest deny(@RequestHeader("X-Tenant-Id") String tenantId,
                              @RequestHeader("X-User-Id") String userId,
                              @PathVariable String id,
                              @RequestBody(required = false) DenyBody body) {
        requireAdmin(userId, tenantId);
        AccessRequest req = load(tenantId, id);
        if (!AccessRequest.PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending request can be denied");
        }
        req.setStatus(AccessRequest.DENIED);
        req.setDecisionNote(trimToNull(body != null ? body.note() : null));
        req.setDecidedBy(userId);
        req.setDecidedAt(LocalDateTime.now());
        return withNames(requests.save(req));
    }

    /* ── authorization + helpers ────────────────────────────── */

    /**
     * Caller must be a platform operator, or a tenant-admin member of the active
     * tenant. Identity is the gateway-asserted X-User-Id (see {@link GatewayAuthFilter}),
     * mirroring {@code OrgAdminController.requireAdmin}'s membership + tenant-admin check.
     */
    private void requireAdmin(String userId, String tenantId) {
        requireTenant(tenantId);
        requireUser(userId);
        List<Group> groups = identityService.createGroupQuery().groupMember(userId).list();
        List<String> tenants = identityService.createTenantQuery().userMember(userId).list()
                .stream().map(t -> t.getId()).toList();
        boolean operator = groups.stream().anyMatch(g -> CAMUNDA_ADMIN_GROUP.equals(g.getId()))
                || tenants.contains(parentClusterId);
        if (operator) return;
        if (!tenants.contains(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of tenant '" + tenantId + "'");
        }
        // Scoped: owner OF THIS tenant, not the global tenant-admin role (which would let an admin
        // of any org approve access here). See TenantOwnership.
        if (!com.luke.engine.tenant.TenantOwnership.isOwner(identityService, userId, tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires org owner (tenant-admin)");
        }
    }

    private AccessRequest load(String tenantId, String id) {
        return requests.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown access request: " + id));
    }

    /** Read-time enrichment: fill requesterName / decidedByName / capabilityName on one request. */
    private AccessRequest withNames(AccessRequest req) {
        withNames(List.of(req));
        return req;
    }

    /** Batch read-time enrichment across requests (one user-directory lookup, cached catalog reads). */
    private List<AccessRequest> withNames(List<AccessRequest> list) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (AccessRequest r : list) {
            if (r.getUserId() != null) ids.add(r.getUserId());
            if (r.getDecidedBy() != null) ids.add(r.getDecidedBy());
        }
        Map<String, String> names = userDirectory.namesFor(ids);
        Map<String, String> capNames = new java.util.HashMap<>();
        for (AccessRequest r : list) {
            if (r.getUserId() != null) r.setRequesterName(names.getOrDefault(r.getUserId(), r.getUserId()));
            if (r.getDecidedBy() != null) r.setDecidedByName(names.getOrDefault(r.getDecidedBy(), r.getDecidedBy()));
            String code = r.getCapabilityCode();
            r.setCapabilityName(capNames.computeIfAbsent(code,
                    c -> capabilities.findByCode(c).map(Capability::getName).orElse(c)));
        }
        return list;
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-User-Id is required");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
