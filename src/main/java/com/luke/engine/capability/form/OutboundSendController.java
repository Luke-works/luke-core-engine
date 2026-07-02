package com.luke.engine.capability.form;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outbound send action for a form definition (Phase 1). Separate from
 * {@link FormDefinitionController} to keep that controller's wiring lean; shares the
 * {@code /api/form-definitions} base path (already gateway-allowlisted + FORMS-gated).
 */
@RestController
@RequestMapping("/api/form-definitions")
public class OutboundSendController {

    private final OutboundSendService service;

    public OutboundSendController(OutboundSendService service) {
        this.service = service;
    }

    /** recipient must carry at least {@code email}; prefill maps fieldKey → preparer value. */
    public record SendBody(Map<String, Object> recipient, Map<String, Object> prefill, Long expiresAt) {}

    @PostMapping("/{id}/send")
    public OutboundSendService.SendResult send(@RequestHeader("X-Tenant-Id") String tenantId,
                                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                                               @PathVariable String id, @RequestBody SendBody body) {
        return service.send(tenantId, id, body.recipient(), body.prefill(), body.expiresAt(), userId);
    }
}
