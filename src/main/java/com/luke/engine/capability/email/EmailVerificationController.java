package com.luke.engine.capability.email;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase-1 org email verification, guarded by the EMAIL capability and tenant-scoped
 * via {@code X-Tenant-Id}. Flow: {@code POST /start} mails an OTP to the org's
 * official address (after the name↔domain check); {@code POST /verify} confirms the
 * code and auto-provisions the tenant's Postmark server.
 */
@RestController
@RequestMapping("/api/email-verification")
public class EmailVerificationController {

    private final EmailVerificationService verification;

    public EmailVerificationController(EmailVerificationService verification) {
        this.verification = verification;
    }

    public record StartBody(String orgName, String email) {}
    public record VerifyBody(String code) {}

    /** Send a one-time code to the org's official email. */
    @PostMapping("/start")
    public EmailVerificationService.VerificationView start(@RequestHeader("X-Tenant-Id") String tenantId,
                                                           @RequestBody StartBody body) {
        requireTenant(tenantId);
        return verification.start(tenantId, body.orgName(), body.email());
    }

    /** Confirm the code; on success the tenant's email server is auto-provisioned. */
    @PostMapping("/verify")
    public EmailVerificationService.VerifyResult verify(@RequestHeader("X-Tenant-Id") String tenantId,
                                                        @RequestBody VerifyBody body) {
        requireTenant(tenantId);
        return verification.verify(tenantId, body.code());
    }

    /** Current verification status for the tenant. */
    @GetMapping
    public EmailVerificationService.VerificationView current(@RequestHeader("X-Tenant-Id") String tenantId) {
        requireTenant(tenantId);
        return verification.current(tenantId);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
