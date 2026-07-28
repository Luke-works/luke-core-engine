package com.luke.engine.recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The per-tenant portal handle round-trips to its tenant id and rejects tamper / wrong secret. */
class PortalTenantTokensTest {

    private final PortalTenantTokens tokens = new PortalTenantTokens("unit-test-secret");

    @Test
    void signThenVerify_roundTripsTenant() {
        assertEquals("tenant-A", tokens.verify(tokens.sign("tenant-A")));
    }

    @Test
    void tamperedSignature_isRejected() {
        String t = tokens.sign("tenant-A");
        String tampered = t.substring(0, t.length() - 1) + (t.endsWith("A") ? "B" : "A");
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(tampered));
    }

    @Test
    void tokenFromDifferentSecret_isRejected() {
        String foreign = new PortalTenantTokens("other-secret").sign("tenant-A");
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(foreign));
    }

    @Test
    void malformedTokens_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(null));
        assertThrows(IllegalArgumentException.class, () -> tokens.verify(""));
        assertThrows(IllegalArgumentException.class, () -> tokens.verify("no-dot"));
    }

    @Test
    void tokensAreUnique_butBothVerify() {
        String a = tokens.sign("t");
        String b = tokens.sign("t");
        assertNotEquals(a, b);
        assertEquals("t", tokens.verify(a));
        assertEquals("t", tokens.verify(b));
    }
}
