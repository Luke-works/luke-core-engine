package com.luke.engine.capability.email;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages a company's dedicated Postmark Server, guarded by the EMAIL capability
 * (read to view, write to provision). Provisioning calls the Postmark Account API
 * and stores the new server's token; the token is never returned (see
 * {@link EmailServer}'s {@code @JsonIgnore}). One server per tenant.
 *
 * <p>Tenant-scoped via {@code X-Tenant-Id}.
 */
@RestController
@RequestMapping("/api/email-servers")
public class EmailServerController {

    private final EmailServerService servers;

    public EmailServerController(EmailServerService servers) {
        this.servers = servers;
    }

    /** Provision this company's Postmark server. Conflicts if one already exists. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmailServer provision(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestBody EmailServerService.ProvisionRequest body) {
        requireTenant(tenantId);
        return servers.provision(tenantId, body);
    }

    /** The current tenant's provisioned server (without the secret token). */
    @GetMapping
    public EmailServer current(@RequestHeader("X-Tenant-Id") String tenantId) {
        requireTenant(tenantId);
        return servers.require(tenantId);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
