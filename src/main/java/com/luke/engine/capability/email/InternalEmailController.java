package com.luke.engine.capability.email;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-to-server email endpoint for BPMN processes / core-engine — the same
 * "send a notification" path a Camunda task needs. Not capability-guarded and not
 * gateway-authenticated; instead it sits behind the shared-secret
 * {@link com.luke.engine.capability.access.InternalAuthFilter} on {@code /api/internal/**},
 * mirroring how core-engine guards its own internal hops.
 *
 * <p>The tenant is supplied by the caller via {@code X-Tenant-Id} (the process
 * knows its tenant); the sender of record is {@code system} unless {@code X-User-Id}
 * is provided. Delegates to the same {@link EmailService} as the tenant API, so
 * every internal send is recorded and listable too.
 */
@RestController
@RequestMapping("/api/internal/emails")
public class InternalEmailController {

    private final EmailService emails;

    public InternalEmailController(EmailService emails) {
        this.emails = emails;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmailMessage send(@RequestHeader("X-Tenant-Id") String tenantId,
                             @RequestHeader(value = "X-User-Id", required = false) String userId,
                             @RequestBody EmailRequest body) {
        requireTenant(tenantId);
        return emails.sendRaw(tenantId, actor(userId), body);
    }

    @PostMapping("/template")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailMessage sendTemplate(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @RequestHeader(value = "X-User-Id", required = false) String userId,
                                     @RequestBody EmailRequest body) {
        requireTenant(tenantId);
        return emails.sendTemplate(tenantId, actor(userId), body);
    }

    private static String actor(String userId) {
        return userId != null && !userId.isBlank() ? userId : "system";
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
