package com.luke.engine.recipient;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated recipient PORTAL API ({@code /api/public/portal/**} — the public space
 * bypasses the tenant gateway by design). Everything under {@code /{tenantTok}} is scoped by the
 * signed per-tenant portal handle; {@code /forms} is authorised by the OTP/magic-link-minted,
 * email-scoped Bearer session token. No tenant header, no account.
 */
@RestController
@RequestMapping("/api/public/portal")
public class PortalController {

    private final PortalService service;

    public PortalController(PortalService service) {
        this.service = service;
    }

    public record ChallengeBody(String email, String channel) {}
    public record VerifyBody(String email, String code) {}
    public record EmailBody(String email) {}
    public record MagicConsumeBody(String token) {}

    /** Send an OTP (email or SMS) to the recipient. Generic response — never leaks whether the email has forms. */
    @PostMapping("/{tenantTok}/challenge")
    public Map<String, Object> challenge(@PathVariable String tenantTok, @RequestBody ChallengeBody body) {
        return service.challenge(tenantTok, body.email(), body.channel());
    }

    /** Verify an OTP → { accessToken, email }. */
    @PostMapping("/{tenantTok}/verify")
    public Map<String, Object> verify(@PathVariable String tenantTok, @RequestBody VerifyBody body) {
        return service.verify(tenantTok, body.email(), body.code());
    }

    /** Email a single-use magic link. Generic response — never leaks whether the email has forms. */
    @PostMapping("/{tenantTok}/magic-link")
    public Map<String, Object> magicLink(@PathVariable String tenantTok, @RequestBody EmailBody body) {
        return service.requestMagicLink(tenantTok, body.email());
    }

    /** Consume a magic-link token → { accessToken, email }. */
    @PostMapping("/{tenantTok}/magic/consume")
    public Map<String, Object> consume(@PathVariable String tenantTok, @RequestBody MagicConsumeBody body) {
        return service.consumeMagicLink(tenantTok, body.token());
    }

    /** The recipient's assigned open items across capabilities (requires the Bearer portal session token). */
    @GetMapping("/items")
    public Map<String, Object> items(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return service.listItems(bearer(auth));
    }

    private static String bearer(String header) {
        if (header == null) return null;
        return header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : header.trim();
    }
}
