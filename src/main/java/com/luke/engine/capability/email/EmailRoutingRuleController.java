package com.luke.engine.capability.email;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages a tenant's inbound-email routing rules.
 *
 * <p>Deliberately mounted UNDER {@code /api/email-boxes} rather than at a path of its own. Both
 * the capability gate ({@link com.luke.engine.capability.access.AccessWebConfig}, EMAIL) and the
 * gateway auth filter are registered on {@code /api/email-boxes/*} as a prefix, so this surface
 * inherits both and cannot be shipped ungated — which is exactly how {@code /api/email-boxes}
 * itself was once left reachable behind nothing but a spoofable {@code X-Tenant-Id} (#20).
 * The literal segment also out-ranks {@code /api/email-boxes/{id}} in Spring's pattern
 * comparator, so it does not collide with box deletion.
 */
@RestController
@RequestMapping("/api/email-boxes/routing-rules")
public class EmailRoutingRuleController {

    private final EmailRoutingRuleService rules;

    public EmailRoutingRuleController(EmailRoutingRuleService rules) {
        this.rules = rules;
    }

    /** All rules for the tenant, in evaluation order. */
    @GetMapping
    public List<EmailRoutingRule> list(@RequestHeader("X-Tenant-Id") String tenantId) {
        requireTenant(tenantId);
        return rules.list(tenantId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmailRoutingRule create(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestBody EmailRoutingRuleService.RuleRequest body) {
        requireTenant(tenantId);
        return rules.create(tenantId, body);
    }

    @PutMapping("/{id}")
    public EmailRoutingRule update(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @PathVariable String id,
                                   @RequestBody EmailRoutingRuleService.RuleRequest body) {
        requireTenant(tenantId);
        return rules.update(tenantId, id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        rules.delete(tenantId, id);
    }

    /** Reorder: the body is the full ordered list of rule ids. Order IS the semantics. */
    @PostMapping("/reorder")
    public List<EmailRoutingRule> reorder(@RequestHeader("X-Tenant-Id") String tenantId,
                                          @RequestBody List<String> orderedIds) {
        requireTenant(tenantId);
        return rules.reorder(tenantId, orderedIds == null ? List.of() : orderedIds);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
