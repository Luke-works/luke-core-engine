package com.luke.engine.capability.email;

import java.util.List;
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
 * Tenant-facing email API, guarded by the EMAIL capability (read to list, write to
 * send — see {@link com.luke.engine.capability.access.AccessWebConfig}). Sends go through
 * {@link EmailService}, which records every attempt as an {@link EmailMessage} row;
 * the response is that row, whose {@code status} reflects the Postmark outcome.
 *
 * <p>Tenant-scoped via {@code X-Tenant-Id}; the sender of record is {@code X-User-Id}.
 */
@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private final EmailService emails;
    private final EmailMessageRepository repository;

    public EmailController(EmailService emails, EmailMessageRepository repository) {
        this.emails = emails;
        this.repository = repository;
    }

    /** Send a raw HTML/text email. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmailMessage send(@RequestHeader("X-Tenant-Id") String tenantId,
                             @RequestHeader(value = "X-User-Id", required = false) String userId,
                             @RequestBody EmailRequest body) {
        requireTenant(tenantId);
        return emails.sendRaw(tenantId, userId, body);
    }

    /** Send a stored Postmark template email. */
    @PostMapping("/template")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailMessage sendTemplate(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @RequestHeader(value = "X-User-Id", required = false) String userId,
                                     @RequestBody EmailRequest body) {
        requireTenant(tenantId);
        return emails.sendTemplate(tenantId, userId, body);
    }

    /** List this tenant's emails, newest first; optional {@code status} filter. */
    @GetMapping
    public List<EmailMessage> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestParam(required = false) String status) {
        requireTenant(tenantId);
        if (status != null && !status.isBlank()) {
            return repository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
        }
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @GetMapping("/{id}")
    public EmailMessage get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown email: " + id));
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
