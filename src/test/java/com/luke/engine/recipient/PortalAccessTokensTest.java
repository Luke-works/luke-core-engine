package com.luke.engine.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Guards the email-scoped portal session token: it round-trips to its (tenant, email), rejects
 * tamper / wrong-secret / expiry, and survives emails containing the payload delimiter.
 */
class PortalAccessTokensTest {

    private final PortalAccessTokens tokens = new PortalAccessTokens("unit-test-secret", 1_800_000L);

    @Test
    void signThenVerify_roundTripsTenantAndEmail() {
        long now = 1_000_000L;
        PortalAccessTokens.PortalRef ref = tokens.verify(tokens.sign("tenant-A", "jo@acme.com", now), now);
        assertEquals("tenant-A", ref.tenantId());
        assertEquals("jo@acme.com", ref.email());
    }

    @Test
    void emailWithDelimiterCharsSurvives() {
        long now = 1_000_000L;
        // The email is base64url-encoded inside the payload, so a '|' can't corrupt the split.
        String weird = "a|b+c@acme.com";
        PortalAccessTokens.PortalRef ref = tokens.verify(tokens.sign("t", weird, now), now);
        assertEquals(weird, ref.email());
    }

    @Test
    void expiredToken_isRejected() {
        long issued = 1_000_000L;
        String t = tokens.sign("t", "jo@acme.com", issued);
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(t, issued + 1_800_001L));
    }

    @Test
    void tamperedSignature_isRejected() {
        String t = tokens.sign("t", "jo@acme.com", 0L);
        String tampered = t.substring(0, t.length() - 1) + (t.endsWith("A") ? "B" : "A");
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(tampered, 0L));
    }

    @Test
    void tokenFromDifferentSecret_isRejected() {
        String foreign = new PortalAccessTokens("other-secret", 1000L).sign("t", "jo@acme.com", 0L);
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(foreign, 0L));
    }

    @Test
    void malformedTokens_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(null, 0L));
        assertThrows(IllegalArgumentException.class, () -> tokens.verify("", 0L));
        assertThrows(IllegalArgumentException.class, () -> tokens.verify("no-dot", 0L));
    }

    @Test
    void tokensAreUnique_butBothVerify() {
        String a = tokens.sign("t", "jo@acme.com", 0L);
        String b = tokens.sign("t", "jo@acme.com", 0L);
        assertNotEquals(a, b); // random padding
        assertEquals("t", tokens.verify(a, 0L).tenantId());
        assertEquals("t", tokens.verify(b, 0L).tenantId());
    }
}
