package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Guards the embed token integrity: a signed token round-trips to its tenant/code,
 * and any tamper / wrong-secret / malformed token is rejected — so the public embed
 * surface can't be forged for another tenant.
 */
class EmbedTokensTest {

    private final EmbedTokens tokens = new EmbedTokens("unit-test-secret");

    @Test
    void signThenVerify_roundTrips() {
        EmbedTokens.EmbedRef ref = tokens.verify(tokens.sign("tenant-A", "form-123"));
        assertEquals("tenant-A", ref.tenantId());
        assertEquals("form-123", ref.code());
    }

    @Test
    void tamperedSignature_isRejected() {
        String t = tokens.sign("tenant-A", "form-123");
        String tampered = t.substring(0, t.length() - 1) + (t.endsWith("A") ? "B" : "A");
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(tampered));
    }

    @Test
    void tokenFromDifferentSecret_isRejected() {
        String foreign = new EmbedTokens("a-different-secret").sign("tenant-A", "form-123");
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
        String a = tokens.sign("t", "c");
        String b = tokens.sign("t", "c");
        assertNotEquals(a, b);                       // random padding → distinct tokens
        assertEquals("t", tokens.verify(a).tenantId());
        assertEquals("t", tokens.verify(b).tenantId());
    }
}
