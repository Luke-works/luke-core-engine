package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.branding.BrandingPolicy;
import com.luke.engine.branding.TenantPlan;
import com.luke.engine.branding.TenantPlanRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The per-form "Developed at Lukeflow" option, end to end through the API:
 * <ul>
 *   <li>the authoring PATCH refuses to hide the badge on a free plan (the browser is not the boundary),
 *   <li>reads report {@code brandingLocked} so the builder can render the option as locked,
 *   <li>the PUBLIC embed payload carries the EFFECTIVE flag (plan already applied).
 * </ul>
 */
class FormBrandingOptionTest {

    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final FormAuditEventRepository audit = mock(FormAuditEventRepository.class);
    private final EmbedTokens tokens = mock(EmbedTokens.class);
    private final com.luke.engine.tenant.UserDirectory dir = mock(com.luke.engine.tenant.UserDirectory.class);
    private final TenantPlanRepository plans = mock(TenantPlanRepository.class);
    private final BrandingPolicy branding = new BrandingPolicy(plans);

    private final FormEmbedSiteRepository embedSites = mock(FormEmbedSiteRepository.class);
    private final FormDefinitionController controller =
            new FormDefinitionController(forms, versions, audit, tokens, dir, branding, embedSites);

    private FormDefinition stored() {
        FormDefinition f = new FormDefinition();
        f.setId("f1");
        f.setTenantId("t1");
        f.setCode("FM-1");
        f.setName("n");
        f.setKind(FormDefinition.KIND_INBOUND);
        f.setSubmissionHandling("COLLECT");
        f.setPublishedVersion(1);
        return f;
    }

    private void paid() {
        when(plans.findById("t1")).thenReturn(Optional.of(new TenantPlan("t1", TenantPlan.PLAN_PAID)));
    }

    private void free() {
        when(plans.findById("t1")).thenReturn(Optional.empty());
    }

    private FormDefinition patch(Boolean showBranding) {
        return controller.patchMeta("t1", "u1", "f1",
                new FormDefinitionController.MetaPatch(null, null, null, null, showBranding));
    }

    /* ── authoring: the paid gate ─────────────────────────────────── */

    @Test
    void newFormsShowTheBadgeByDefault() {
        assertThat(stored().isShowBranding()).isTrue();
    }

    @Test
    void freeTenantCannotTurnTheBadgeOff() {
        free();
        FormDefinition f = stored();
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> patch(false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.PAYMENT_REQUIRED));
        assertThat(f.isShowBranding()).isTrue(); // unchanged
    }

    @Test
    void paidTenantCanTurnTheBadgeOff() {
        paid();
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(stored()));
        when(forms.save(any())).thenAnswer(a -> a.getArgument(0));

        assertThat(patch(false).isShowBranding()).isFalse();
    }

    @Test
    void turningTheBadgeBackOnIsAlwaysAllowed() {
        free();
        FormDefinition f = stored();
        f.setShowBranding(false); // e.g. set while the tenant was still paying
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(f));
        when(forms.save(any())).thenAnswer(a -> a.getArgument(0));

        assertThat(patch(true).isShowBranding()).isTrue();
    }

    @Test
    void omittingTheFieldLeavesTheSettingAlone() {
        free();
        FormDefinition f = stored();
        f.setShowBranding(false);
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(f));
        when(forms.save(any())).thenAnswer(a -> a.getArgument(0));

        // A name-only PATCH from a downgraded tenant must NOT trip the paid gate, and must not
        // silently rewrite their stored preference either.
        FormDefinition out = controller.patchMeta("t1", "u1", "f1",
                new FormDefinitionController.MetaPatch("Renamed", null, null, null, null));
        assertThat(out.getName()).isEqualTo("Renamed");
        assertThat(out.isShowBranding()).isFalse();
    }

    @Test
    void reStatingTheCurrentValueIsNotBlockedOnAFreePlan() {
        free();
        FormDefinition f = stored();
        f.setShowBranding(false);
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(f));
        when(forms.save(any())).thenAnswer(a -> a.getArgument(0));

        // Idempotent PATCH (the UI sends the whole settings form): false → false is a no-op, not a 402.
        assertThat(patch(false).isShowBranding()).isFalse();
    }

    /* ── reads: the lock hint for the builder ─────────────────────── */

    @Test
    void readsTellTheBuilderWhetherTheOptionIsLocked() {
        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(stored()));
        when(dir.namesFor(any())).thenReturn(Map.of());

        free();
        assertThat(controller.get("t1", "f1").isBrandingLocked()).isTrue();

        when(forms.findByIdAndTenantId("f1", "t1")).thenReturn(Optional.of(stored()));
        paid();
        assertThat(controller.get("t1", "f1").isBrandingLocked()).isFalse();
    }

    /* ── public embed payload: the effective flag ─────────────────── */

    private Map<String, Object> render(FormDefinition form) {
        EmbedFormResolver resolver = mock(EmbedFormResolver.class);
        when(resolver.resolve("tok")).thenReturn(new EmbedFormResolver.Resolved(
                new EmbedTokens.EmbedRef("t1", "FM-1", 0), form));
        FormVersion v = new FormVersion();
        v.setSchema("{}");
        when(versions.findByFormIdAndVersion("f1", 1)).thenReturn(Optional.of(v));

        com.luke.engine.web.FixedWindowRateLimiter limiter =
                mock(com.luke.engine.web.FixedWindowRateLimiter.class);
        FormEmbedController embed = new FormEmbedController(resolver, versions,
                mock(FormInstanceRepository.class), mock(FormSubmissionService.class), limiter, branding,
                TurnstileVerifiers.disabled(), 60, 120, 20, 40);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.7");
        return embed.render("tok", req, mock(HttpServletResponse.class));
    }

    @Test
    void embedPayloadForcesTheBadgeOnForAFreeTenant() {
        free();
        FormDefinition f = stored();
        f.setShowBranding(false); // stored preference from a lapsed paid plan
        assertThat(render(f).get("showBranding")).isEqualTo(true);
    }

    @Test
    void embedPayloadHonoursAPaidTenantsChoice() {
        paid();
        FormDefinition off = stored();
        off.setShowBranding(false);
        assertThat(render(off).get("showBranding")).isEqualTo(false);

        assertThat(render(stored()).get("showBranding")).isEqualTo(true);
    }

    /* Guard: the rate limiter is still consulted before any of the above (regression fence for #55). */
    @Test
    void renderStillRateLimitsBeforeResolving() {
        free();
        com.luke.engine.web.FixedWindowRateLimiter limiter =
                mock(com.luke.engine.web.FixedWindowRateLimiter.class);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down"))
                .when(limiter).enforce(anyString(), anyInt(), any());
        EmbedFormResolver resolver = mock(EmbedFormResolver.class);
        FormEmbedController embed = new FormEmbedController(resolver, versions,
                mock(FormInstanceRepository.class), mock(FormSubmissionService.class), limiter, branding,
                TurnstileVerifiers.disabled(), 60, 120, 20, 40);
        HttpServletRequest req = mock(HttpServletRequest.class);
        assertThatThrownBy(() -> embed.render("tok", req, mock(HttpServletResponse.class)))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(resolver);
    }
}
