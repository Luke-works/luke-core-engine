package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.access.GatewayTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** #56: prod (strict) boot must fail if the gateway/operator auth layers fail open. */
class AuthHardeningGuardTest {

    private GatewayTokenVerifier verifier(boolean enabled) {
        GatewayTokenVerifier v = mock(GatewayTokenVerifier.class);
        when(v.isEnabled()).thenReturn(enabled);
        return v;
    }

    private MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    void identifiesOpenLayers() {
        assertEquals(2, AuthHardeningGuard.openAuthLayers(false, false).size());
        assertTrue(AuthHardeningGuard.openAuthLayers(true, true).isEmpty());
        assertEquals(1, AuthHardeningGuard.openAuthLayers(true, false).size());
    }

    @Test
    void strictWithFailOpenLayerRefusesToStart() {
        assertThrows(IllegalStateException.class,
                () -> new AuthHardeningGuard(verifier(false), "", true).verify());
    }

    @Test
    void strictWithBothConfiguredBoots() {
        assertDoesNotThrow(() -> new AuthHardeningGuard(verifier(true), "operator", true).verify());
    }

    @Test
    void lenientOnlyWarns() {
        assertDoesNotThrow(() -> new AuthHardeningGuard(verifier(false), "", false).verify());
    }

    @Test
    void prodProfileForcesStrictEvenWithFlagUnset() {
        // 4-arg (Spring) constructor: prod profile flips strict on without the opt-in flag.
        assertThrows(IllegalStateException.class,
                () -> new AuthHardeningGuard(verifier(false), "", false, env("postgres", "prod")).verify());
    }

    @Test
    void postgresOnlyStaysLenient() {
        // dev/qa run postgres only and lack the operator credential — must NOT crash.
        assertDoesNotThrow(
                () -> new AuthHardeningGuard(verifier(false), "", false, env("postgres")).verify());
    }
}
