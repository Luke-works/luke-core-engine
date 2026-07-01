package com.luke.engine.capability.phone;

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
 * Tenant-facing phone API, guarded by the PHONE capability (read to list, write to place calls —
 * see {@link com.luke.engine.capability.access.AccessWebConfig}). Outbound calls go through
 * {@link PhoneCallService}, which records every call as a {@link PhoneCall} row; the response is
 * that row, whose {@code status} reflects the Vapi outcome.
 *
 * <p>Tenant-scoped via {@code X-Tenant-Id}; the initiator of record is {@code X-User-Id}.
 */
@RestController
@RequestMapping("/api/phone-calls")
public class PhoneController {

    private final PhoneCallService callService;
    private final PhoneCallRepository repository;

    public PhoneController(PhoneCallService callService, PhoneCallRepository repository) {
        this.callService = callService;
        this.repository = repository;
    }

    /** Place an outbound call. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PhoneCall call(@RequestHeader("X-Tenant-Id") String tenantId,
                          @RequestHeader(value = "X-User-Id", required = false) String userId,
                          @RequestBody OutboundCallRequest body) {
        requireTenant(tenantId);
        return callService.placeOutbound(tenantId, userId, body);
    }

    private static final int MAX_PAGE = 200;
    private static final int DEFAULT_PAGE = 50;

    /** A bounded page of calls plus the full server-side total. */
    public record PagedCalls(List<PhoneCall> items, long total, int firstResult, int maxResults) {}

    /**
     * List this tenant's calls, newest first; optional {@code status} or {@code direction} filter.
     * Paged + size-capped server-side — calls accrue monotonically, so the whole history is never
     * loaded at once.
     */
    @GetMapping
    public PagedCalls list(@RequestHeader("X-Tenant-Id") String tenantId,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) String direction,
                           @RequestParam(defaultValue = "0") int firstResult,
                           @RequestParam(defaultValue = "" + DEFAULT_PAGE) int maxResults) {
        requireTenant(tenantId);
        int size = Math.min(Math.max(1, maxResults), MAX_PAGE);
        int offset = Math.max(0, firstResult);
        Pageable pageable = PageRequest.of(offset / size, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PhoneCall> result;
        if (status != null && !status.isBlank()) {
            result = repository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else if (direction != null && !direction.isBlank()) {
            result = repository.findByTenantIdAndDirection(tenantId, direction.toUpperCase(), pageable);
        } else {
            result = repository.findByTenantId(tenantId, pageable);
        }
        return new PagedCalls(result.getContent(), result.getTotalElements(), offset, size);
    }

    @GetMapping("/{id}")
    public PhoneCall get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown call: " + id));
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
