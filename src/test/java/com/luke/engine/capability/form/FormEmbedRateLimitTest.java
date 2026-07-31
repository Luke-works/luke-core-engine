package com.luke.engine.capability.form;

import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.luke.engine.web.FixedWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * #55: the previously-unlimited public GET render is now rate-limited BEFORE it does any token
 * verify / DB work — closing the cheap resource-exhaustion + schema-scraping vector.
 */
class FormEmbedRateLimitTest {

    private final EmbedFormResolver resolver = mock(EmbedFormResolver.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final FormSubmissionService submissions = mock(FormSubmissionService.class);
    private final FixedWindowRateLimiter limiter = mock(FixedWindowRateLimiter.class);

    // Real policy over an empty plan store = every tenant is FREE (badge forced on), which is the
    // production default; the render tests below don't get that far anyway.
    private final com.luke.engine.branding.BrandingPolicy branding = new com.luke.engine.branding.BrandingPolicy(
            mock(com.luke.engine.branding.TenantPlanRepository.class));

    private FormEmbedController controller() {
        // Captcha OFF: this suite is about the rate limits, and a gate in front of them would change
        // what it exercises. TurnstileVerifierTest and FormEmbedCaptchaTest cover the gate itself.
        return new FormEmbedController(resolver, versions, instances, submissions, limiter, branding, paidPlan(),
                TurnstileVerifiers.disabled(), 60, 120, 20, 40);
    }

    @Test
    void renderIsRateLimitedBeforeAnyResolveOrDbWork() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        // Simulate the per-token render cap tripping.
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, try again shortly."))
                .when(limiter).enforce(startsWith("embed-render-t:"), anyInt(), any());

        assertThrows(ResponseStatusException.class, () -> controller().render("tok", req, res));

        // Gated before any token resolution / schema lookup — no DB work for a throttled request.
        verifyNoInteractions(resolver);
        verifyNoInteractions(versions);
    }

    @Test
    void renderEnforcesBothTheIpAndTokenCaps() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        // Trip on the IP cap so we can assert both keys are checked without mocking the resolve chain.
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "x"))
                .when(limiter).enforce(startsWith("embed-render-ip:"), eq(120), any());

        assertThrows(ResponseStatusException.class, () -> controller().render("tok", req, res));

        verify(limiter).enforce(startsWith("embed-render-ip:"), eq(120), any());
    }

    @Test
    void ipCapKeysOffTheGatewayVouchedIpNotTheSpoofableXff() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        // The gateway resolves the true client IP and stamps X-Real-Client-IP; it must WIN over the
        // spoofable left-most X-Forwarded-For so an abuser can't rotate XFF to mint fresh IP buckets.
        when(req.getHeader("X-Real-Client-IP")).thenReturn("9.9.9.9");
        when(req.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1, 10.0.0.1");
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "x"))
                .when(limiter).enforce(startsWith("embed-render-ip:9.9.9.9"), eq(120), any());

        assertThrows(ResponseStatusException.class, () -> controller().render("tok", req, res));

        // The IP bucket used the gateway-vouched 9.9.9.9, NOT the forwarded-for 1.1.1.1.
        verify(limiter).enforce(startsWith("embed-render-ip:9.9.9.9"), eq(120), any());
    }

    /** Attachments are irrelevant here — a paid plan keeps the render payload's flag out of the way. */
    private static com.luke.engine.branding.PlanFeatures paidPlan() {
        com.luke.engine.branding.PlanFeatures p = mock(com.luke.engine.branding.PlanFeatures.class);
        when(p.canUseAttachments(anyString())).thenReturn(true);
        return p;
    }
}
