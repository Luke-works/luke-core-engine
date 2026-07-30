package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * Submission provenance — the IP / device / door recorded so a completed form is enforceable.
 *
 * <p>The IP preference order is the security-relevant part: the gateway-stamped {@code X-Real-Client-IP}
 * (which the gateway resolves and strips from client input) must win over the forgeable
 * {@code X-Forwarded-For}, or an abuser could write whatever address they liked into the evidence.
 */
class SubmissionProvenanceTest {

    private HttpServletRequest req(String realClientIp, String xff, String xRealIp, String remote, String ua) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getHeader("X-Real-Client-IP")).thenReturn(realClientIp);
        when(r.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(r.getHeader("X-Real-IP")).thenReturn(xRealIp);
        when(r.getHeader("User-Agent")).thenReturn(ua);
        when(r.getRemoteAddr()).thenReturn(remote);
        return r;
    }

    @Test
    void prefersTheGatewayVouchedIpOverForgeableHeaders() {
        HttpServletRequest r = req("203.0.113.7", "1.2.3.4, 5.6.7.8", "9.9.9.9", "10.0.0.1", "UA/1");
        assertThat(SubmissionSource.clientIp(r)).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackThroughXffThenXRealIpThenSocket() {
        assertThat(SubmissionSource.clientIp(req(null, "1.2.3.4, 5.6.7.8", "9.9.9.9", "10.0.0.1", null)))
                .isEqualTo("1.2.3.4");
        assertThat(SubmissionSource.clientIp(req(null, null, "9.9.9.9", "10.0.0.1", null)))
                .isEqualTo("9.9.9.9");
        assertThat(SubmissionSource.clientIp(req(null, null, null, "10.0.0.1", null)))
                .isEqualTo("10.0.0.1");
    }

    @Test
    void blankHeadersAreIgnoredRatherThanRecordedAsEmpty() {
        assertThat(SubmissionSource.clientIp(req("   ", "", "  ", "10.0.0.1", null))).isEqualTo("10.0.0.1");
        assertThat(SubmissionSource.clientIp(req(null, null, null, "", null))).isNull();
    }

    @Test
    void capturesUserAgentAndDoor() {
        SubmissionSource s = SubmissionSource.from(
                req("203.0.113.7", null, null, null, "Mozilla/5.0 (Macintosh)"), SubmissionSource.VIA_EMBED);
        assertThat(s.ip()).isEqualTo("203.0.113.7");
        assertThat(s.userAgent()).isEqualTo("Mozilla/5.0 (Macintosh)");
        assertThat(s.via()).isEqualTo(SubmissionSource.VIA_EMBED);
    }

    @Test
    void anAbsurdlyLongUserAgentIsTruncatedToFitTheColumn() {
        String huge = "U".repeat(5000);
        SubmissionSource s = SubmissionSource.from(req("1.1.1.1", null, null, null, huge), SubmissionSource.VIA_APP);
        assertThat(s.userAgent()).hasSize(512);
    }

    @Test
    void aMissingRequestStillYieldsAUsableRecord() {
        SubmissionSource s = SubmissionSource.from(null, SubmissionSource.VIA_RESPOND);
        assertThat(s.via()).isEqualTo(SubmissionSource.VIA_RESPOND);
        assertThat(s.ip()).isNull();
        assertThat(s.userAgent()).isNull();
        assertThat(SubmissionSource.clientIp(null)).isNull();
    }
}
