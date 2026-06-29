package com.luke.engine.capability.minion;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The AUTHENTICATED secure minion proxy. Reached as {@code POST /api/minions/{minion}} by internal,
 * logged-in form surfaces (FormFill / Inbox). The gateway authenticates the user and sets the
 * {@code X-Tenant-Id} header; the operation runs server-side so provider credentials never reach the
 * browser. The public/embed counterpart is {@link PublicMinionController}.
 */
@RestController
@RequestMapping("/api/minions")
public class MinionController {

    /** Per-tenant cap for a single operation, per minute. */
    private static final int MAX_PER_TENANT_PER_MIN = 60;

    private final MinionRegistry registry;
    private final MinionRateLimiter rateLimiter;

    public MinionController(MinionRegistry registry, MinionRateLimiter rateLimiter) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{minion}")
    public Object call(@RequestHeader("X-Tenant-Id") String tenantId,
                       @PathVariable String minion,
                       @RequestBody(required = false) Map<String, Object> params) {
        if (!StringUtils.hasText(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing tenant.");
        }
        Minion m = registry.get(minion);
        if (m == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown operation.");
        }
        rateLimiter.check("t:" + tenantId + ":" + minion, MAX_PER_TENANT_PER_MIN);
        return m.handle(tenantId, params != null ? params : Map.of());
    }
}
