package com.luke.engine.capability.phone;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-to-server outbound-call endpoint for BPMN processes / core-engine — the "place a call"
 * path a Camunda task needs (e.g. an appointment-reminder or confirmation call). Not
 * capability-guarded and not gateway-authenticated; instead it sits behind the shared-secret
 * {@link com.luke.engine.capability.access.InternalAuthFilter} on {@code /api/internal/**},
 * mirroring {@code InternalEmailController}.
 *
 * <p>The tenant is supplied by the caller via {@code X-Tenant-Id} (the process knows its tenant);
 * the initiator of record is {@code system} unless {@code X-User-Id} is provided. Delegates to the
 * same {@link PhoneCallService} as the tenant API, so every internal call is recorded and listable.
 */
@RestController
@RequestMapping("/api/internal/phone-calls")
public class InternalPhoneController {

    private final PhoneCallService callService;

    public InternalPhoneController(PhoneCallService callService) {
        this.callService = callService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PhoneCall call(@RequestHeader("X-Tenant-Id") String tenantId,
                          @RequestHeader(value = "X-User-Id", required = false) String userId,
                          @RequestBody OutboundCallRequest body) {
        requireTenant(tenantId);
        return callService.placeOutbound(tenantId, actor(userId), body);
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
