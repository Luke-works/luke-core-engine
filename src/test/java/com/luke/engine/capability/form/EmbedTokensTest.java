package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Guards the embed token integrity: a signed token round-trips to its tenant/code/version,
 * and any tamper / wrong-secret / malformed token is rejected — so the public embed
 * surface can't be forged for another tenant or replayed past a revocation.
 */
class EmbedTokensTest {

    private final EmbedTokens tokens = new EmbedTokens("unit-test-secret");

    @Test
    void signThenVerify_roundTripsIncludingKeyVersion() {
        EmbedTokens.EmbedRef ref = tokens.verify(tokens.sign("tenant-A", "form-123", 7));
        assertEquals("tenant-A", ref.tenantId());
        assertEquals("form-123", ref.code());
        assertEquals(7, ref.keyVersion());
    }

    @Test
    void keyVersionIsTamperProof() {
        // The version is inside the signed body, so it can't be swapped without breaking the HMAC.
        String v0 = tokens.sign("t", "c", 0);
        String v1 = tokens.sign("t", "c", 1);
        assertNotEquals(v0, v1);
        assertEquals(0, tokens.verify(v0).keyVersion());
        assertEquals(1, tokens.verify(v1).keyVersion());
    }

    @Test
    void tamperedSignature_isRejected() {
        String t = tokens.sign("tenant-A", "form-123", 0);
        String tampered = t.substring(0, t.length() - 1) + (t.endsWith("A") ? "B" : "A");
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(tampered));
    }

    @Test
    void tokenFromDifferentSecret_isRejected() {
        String foreign = new EmbedTokens("a-different-secret").sign("tenant-A", "form-123", 0);
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(foreign));
    }

    @Test
    void malformedTokens_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(null));
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(""));
        assertThrows(IllegalArgumentException.class, () -> tokens.verify("no-dot-here"));
        assertThrows(IllegalArgumentException.class, () -> tokens.verify("body."));
    }

    @Test
    void tokensAreUnique_butAllVerify() {
        String a = tokens.sign("t", "c", 0);
        String b = tokens.sign("t", "c", 0);
        assertNotEquals(a, b); // random padding → distinct tokens
        assertEquals("t", tokens.verify(a).tenantId());
        assertEquals("t", tokens.verify(b).tenantId());
    }
}
