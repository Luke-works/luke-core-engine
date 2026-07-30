package com.luke.engine.capability.form;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated outbound fill surface (Phase 2), reached as {@code /api/public/form-instances/**}
 * (the {@code /api/public/**} space bypasses the tenant gateway by design). Auth is the opaque instance
 * token plus an OTP-verified, short-lived Bearer access token — no tenant header.
 */
@RestController
@RequestMapping("/api/public/form-instances")
public class PublicFormInstanceController {

    private final PublicFormInstanceService service;

    public PublicFormInstanceController(PublicFormInstanceService service) {
        this.service = service;
    }

    public record CodeBody(String code) {}
    public record DataBody(Map<String, Object> data) {}

    /** Mail a one-time code to the recipient. */
    @PostMapping("/{token}/otp")
    public Map<String, Object> requestOtp(@PathVariable String token) {
        return service.requestOtp(token);
    }

    /** Verify the code → { accessToken }. */
    @PostMapping("/{token}/verify")
    public Map<String, Object> verify(@PathVariable String token, @RequestBody CodeBody body) {
        return service.verify(token, body.code());
    }

    /** Render the prefilled form (requires the Bearer access token). */
    @GetMapping("/{token}")
    public Map<String, Object> render(@PathVariable String token,
                                      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return service.render(token, bearer(auth));
    }

    /** Autosave in-progress answers. */
    @PatchMapping("/{token}")
    public Map<String, Object> save(@PathVariable String token,
                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                    @RequestBody DataBody body) {
        service.save(token, bearer(auth), body.data());
        return Map.of("ok", true);
    }

    /** Final submit. The request is passed through so the submission's provenance (IP / user-agent) is
     *  captured at the edge — see {@link SubmissionSource}. */
    @PostMapping("/{token}/submit")
    public Map<String, Object> submit(@PathVariable String token,
                                      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      @RequestBody(required = false) DataBody body,
                                      jakarta.servlet.http.HttpServletRequest request) {
        return service.submit(token, bearer(auth), body != null ? body.data() : null,
                SubmissionSource.from(request, SubmissionSource.VIA_RESPOND));
    }

    private static String bearer(String header) {
        if (header == null) return null;
        return header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : header.trim();
    }
}
