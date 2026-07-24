package com.luke.engine.recipient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.email.EmailMessage;
import com.luke.engine.capability.email.EmailRequest;
import com.luke.engine.capability.email.EmailService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * The capability-agnostic recipient portal: email-scoped challenge/verify/magic-link + the "my items"
 * listing aggregated across {@link RecipientItemProvider}s, with the privacy guard (no send / generic
 * response when the email has no items) and the SMS seam (refused until a gateway is configured).
 */
class PortalServiceTest {

    private final PortalOtpRepository otps = mock(PortalOtpRepository.class);
    private final PortalMagicLinkRepository magicLinks = mock(PortalMagicLinkRepository.class);
    private final PortalTenantTokens tenantTokens = new PortalTenantTokens("unit-tenant-secret");
    private final PortalAccessTokens accessTokens = new PortalAccessTokens("unit-portal-secret", 1_800_000L);
    private final EmailService emails = mock(EmailService.class);
    private final RecipientItemProvider provider = mock(RecipientItemProvider.class);
    // Synchronous executor so off-thread sends run inline and assertions on emails.sendRaw hold.
    private final PortalService service = new PortalService(
            otps, magicLinks, tenantTokens, accessTokens, emails,
            List.of(new EmailPortalOtpSender(emails), new SmsPortalOtpSender(false)),
            List.of(provider),
            Runnable::run,
            "http://localhost:8080/");

    private final String tenantTok = tenantTokens.sign("t1");

    private RecipientItem item() {
        return new RecipientItem("form", "inv_1", "Intake", "SENT", 1_700_000_000_000L, null);
    }

    /** Recipient has ≥1 item (across providers). */
    private void stubHasItems() {
        when(provider.itemsFor("t1", "jo@acme.com")).thenReturn(List.of(item()));
    }

    private EmailMessage okMail() {
        EmailMessage m = mock(EmailMessage.class);
        when(m.getStatus()).thenReturn("SENT");
        return m;
    }

    // ── challenge (OTP) ──────────────────────────────────────────────────────────

    @Test
    void challengeEmail_storesAndSendsWhenRecipientHasItems() {
        stubHasItems();
        when(otps.findByTenantIdAndRecipientEmail("t1", "jo@acme.com")).thenReturn(Optional.empty());
        EmailMessage msg = okMail();
        when(emails.sendRaw(eq("t1"), any(), any(EmailRequest.class))).thenReturn(msg);

        Map<String, Object> out = service.challenge(tenantTok, "Jo@Acme.com", "email"); // mixed case

        assertThat(out).containsEntry("ok", true).containsEntry("channel", "EMAIL");
        verify(otps).save(any(PortalOtp.class));
        verify(emails).sendRaw(eq("t1"), any(), any(EmailRequest.class));
    }

    @Test
    void challenge_withNoItemsIsGenericAndSendsNothing() {
        // provider returns empty (Mockito default for a List-returning method) → no items for this email
        Map<String, Object> out = service.challenge(tenantTok, "nobody@acme.com", "email");

        assertThat(out).containsEntry("ok", true);
        verify(otps, never()).save(any());
        verify(emails, never()).sendRaw(anyString(), any(), any(EmailRequest.class));
    }

    @Test
    void challenge_rapidResendIsSilentlyThrottled_notADistinguishable429() {
        stubHasItems();
        PortalOtp recent = otp("111111", "s", 0, LocalDateTime.now().plusMinutes(5));
        recent.setCreatedAt(LocalDateTime.now()); // just sent
        when(otps.findByTenantIdAndRecipientEmail("t1", "jo@acme.com")).thenReturn(Optional.of(recent));

        Map<String, Object> out = service.challenge(tenantTok, "jo@acme.com", "email");

        assertThat(out).containsEntry("ok", true); // generic 200, no exception
        verify(otps, never()).save(any());
        verify(emails, never()).sendRaw(anyString(), any(), any(EmailRequest.class));
    }

    @Test
    void challengeSms_isRefusedWhileGatewayUnavailable() {
        assertThatThrownBy(() -> service.challenge(tenantTok, "jo@acme.com", "sms"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("available");
        verify(otps, never()).save(any());
    }

    @Test
    void challenge_withBadTenantHandleIs404() {
        assertThatThrownBy(() -> service.challenge("forged", "jo@acme.com", "email"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("portal link");
    }

    // ── verify ───────────────────────────────────────────────────────────────────

    private PortalOtp otp(String code, String salt, int attempts, LocalDateTime expiresAt) {
        PortalOtp o = new PortalOtp();
        o.setTenantId("t1");
        o.setRecipientEmail("jo@acme.com");
        o.setChannel("EMAIL");
        o.setCodeHash(PortalService.hash(code, salt));
        o.setCodeSalt(salt);
        o.setAttempts(attempts);
        o.setExpiresAt(expiresAt);
        return o;
    }

    @Test
    void verify_wrongCodeCountsAttempt_rightCodeMintsSessionForThatEmail() {
        when(otps.findByTenantIdAndRecipientEmail("t1", "jo@acme.com"))
                .thenReturn(Optional.of(otp("123456", "s", 0, LocalDateTime.now().plusMinutes(5))));

        assertThatThrownBy(() -> service.verify(tenantTok, "jo@acme.com", "000000"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("isn't right");
        verify(otps).save(any(PortalOtp.class)); // attempt incremented

        Map<String, Object> out = service.verify(tenantTok, "jo@acme.com", "123456");
        PortalAccessTokens.PortalRef ref = accessTokens.verify(out.get("accessToken").toString(), System.currentTimeMillis());
        assertThat(ref.tenantId()).isEqualTo("t1");
        assertThat(ref.email()).isEqualTo("jo@acme.com");
        verify(otps).delete(any(PortalOtp.class)); // single-use
    }

    @Test
    void verify_withNoCodeOnFileReturnsTheSame401AsAWrongCode() {
        when(otps.findByTenantIdAndRecipientEmail("t1", "nobody@acme.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify(tenantTok, "nobody@acme.com", "123456"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("isn't right")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void verify_expiredCodeIsRejected() {
        when(otps.findByTenantIdAndRecipientEmail("t1", "jo@acme.com"))
                .thenReturn(Optional.of(otp("123456", "s", 0, LocalDateTime.now().minusMinutes(1))));
        assertThatThrownBy(() -> service.verify(tenantTok, "jo@acme.com", "123456"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expired");
    }

    // ── magic link ───────────────────────────────────────────────────────────────

    @Test
    void magicLink_requestClearsPriorAndSends_thenConsumesOnce() {
        stubHasItems();
        when(magicLinks.findFirstByTenantIdAndRecipientEmailOrderByCreatedAtDesc("t1", "jo@acme.com"))
                .thenReturn(Optional.empty());
        Map<String, Object> req = service.requestMagicLink(tenantTok, "jo@acme.com");
        assertThat(req).containsEntry("ok", true);
        verify(magicLinks).deleteByTenantIdAndRecipientEmail("t1", "jo@acme.com");
        verify(magicLinks).save(any(PortalMagicLink.class));
        verify(emails).sendRaw(eq("t1"), any(), any(EmailRequest.class));

        PortalMagicLink link = link(LocalDateTime.now().plusMinutes(10), null);
        when(magicLinks.findByTokenHash(PortalService.sha256("rawtok"))).thenReturn(Optional.of(link));
        when(magicLinks.markConsumed(any(), any())).thenReturn(1); // this caller wins the atomic claim

        Map<String, Object> out = service.consumeMagicLink(tenantTok, "rawtok");
        PortalAccessTokens.PortalRef ref = accessTokens.verify(out.get("accessToken").toString(), System.currentTimeMillis());
        assertThat(ref.email()).isEqualTo("jo@acme.com");
        verify(magicLinks).markConsumed(any(), any());
    }

    @Test
    void magicLink_consumeLosesTheAtomicRace() {
        PortalMagicLink link = link(LocalDateTime.now().plusMinutes(10), null);
        when(magicLinks.findByTokenHash(PortalService.sha256("rawtok"))).thenReturn(Optional.of(link));
        when(magicLinks.markConsumed(any(), any())).thenReturn(0); // a concurrent consume already claimed it

        assertThatThrownBy(() -> service.consumeMagicLink(tenantTok, "rawtok"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("used");
    }

    private PortalMagicLink link(LocalDateTime expiresAt, LocalDateTime consumedAt) {
        PortalMagicLink link = new PortalMagicLink();
        link.setTenantId("t1");
        link.setRecipientEmail("jo@acme.com");
        link.setTokenHash(PortalService.sha256("rawtok"));
        link.setCreatedAt(LocalDateTime.now());
        link.setExpiresAt(expiresAt);
        link.setConsumedAt(consumedAt);
        return link;
    }

    // ── listing ──────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void listItems_aggregatesAssignedOpenItemsAcrossProviders() {
        String session = accessTokens.sign("t1", "jo@acme.com", System.currentTimeMillis());
        stubHasItems();

        Map<String, Object> out = service.listItems(session);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("type", "form")
                .containsEntry("token", "inv_1")
                .containsEntry("title", "Intake");
    }

    @Test
    void listItems_rejectsAnInvalidSession() {
        assertThatThrownBy(() -> service.listItems("garbage"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Verify your email");
    }
}
