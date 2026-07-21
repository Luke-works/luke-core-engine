package com.luke.engine.capability.email;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages a tenant's email boxes — registered send-from (OUTBOUND) / receive-at (INBOUND)
 * addresses on its verified sender domain. Guarded by the EMAIL capability and tenant-scoped
 * via {@code X-Tenant-Id}, mirroring {@link EmailServerController}.
 */
@RestController
@RequestMapping("/api/email-boxes")
public class EmailBoxController {

    private final EmailBoxService boxes;

    public EmailBoxController(EmailBoxService boxes) {
        this.boxes = boxes;
    }

    /** All boxes for the current tenant (inbound + outbound). */
    @GetMapping
    public List<EmailBox> list(@RequestHeader("X-Tenant-Id") String tenantId) {
        requireTenant(tenantId);
        return boxes.list(tenantId);
    }

    /** Register a new inbound or outbound box. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmailBoxService.RegisterResult register(@RequestHeader("X-Tenant-Id") String tenantId,
                                                   @RequestBody EmailBoxService.RegisterRequest body) {
        requireTenant(tenantId);
        return boxes.register(tenantId, body);
    }

    /** Remove a box (drops our row; Postmark stream/history is left intact). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        boxes.delete(tenantId, id);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
