package com.luke.engine.capability.email;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    private static final int MAX_PAGE = 200;
    private static final int DEFAULT_PAGE = 50;

    /** A bounded page of emails plus the full server-side total (#52). */
    public record PagedEmails(List<EmailMessage> items, long total, int firstResult, int maxResults) {}

    /**
     * List this tenant's emails, newest first; optional {@code status} filter. Paged +
     * size-capped server-side (#52) — emails accrue monotonically, so the whole history
     * is never loaded at once. {@code firstResult}/{@code maxResults} are an offset/limit;
     * page size is clamped to {@value #MAX_PAGE}.
     */
    @GetMapping
    public PagedEmails list(@RequestHeader("X-Tenant-Id") String tenantId,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "0") int firstResult,
                            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int maxResults) {
        requireTenant(tenantId);
        int size = Math.min(Math.max(1, maxResults), MAX_PAGE);
        int offset = Math.max(0, firstResult);
        Pageable pageable = PageRequest.of(offset / size, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<EmailMessage> result = (status != null && !status.isBlank())
                ? repository.findByTenantIdAndStatus(tenantId, status, pageable)
                : repository.findByTenantId(tenantId, pageable);
        return new PagedEmails(result.getContent(), result.getTotalElements(), offset, size);
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
