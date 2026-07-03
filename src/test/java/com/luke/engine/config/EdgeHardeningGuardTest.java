package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Prod fail-fast for CORS + webhook edge defaults. Mirrors {@code InsecureKeyGuardTest}: dev
 * (non-strict) only warns; strict throws on the localhost CORS default and on a webhook whose
 * secret is set but not enforced — but never on an unused (secret-less) webhook.
 */
class EdgeHardeningGuardTest {

    private static void verify(EdgeHardeningGuard g) {
        g.verify();
    }

    @Test
    void nonStrictNeverThrowsEvenWithLocalhostCors() {
        assertDoesNotThrow(() -> verify(new EdgeHardeningGuard(
                EdgeHardeningGuard.DEV_CORS_DEFAULT, "", false, "", false, /*requireStrong=*/false)));
    }

    @Test
    void strictThrowsOnLocalhostCorsDefault() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> verify(new EdgeHardeningGuard(
                EdgeHardeningGuard.DEV_CORS_DEFAULT, "", false, "", false, /*requireStrong=*/true)));
        assertTrue(ex.getMessage().contains("allowed-origins"));
    }

    @Test
    void strictAllowsRealOriginsAndNoWebhookSecrets() {
        assertDoesNotThrow(() -> verify(new EdgeHardeningGuard(
                "https://app.lukeflow.com", "", false, "", false, /*requireStrong=*/true)));
    }

    @Test
    void strictThrowsWhenWebhookSecretSetButNotEnforced() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> verify(new EdgeHardeningGuard(
                "https://app.lukeflow.com", "a-vapi-secret", /*vapiRequire=*/false,
                "a-nango-key", /*nangoRequire=*/false, /*requireStrong=*/true)));
        assertTrue(ex.getMessage().contains("Vapi") || ex.getMessage().contains("Nango"));
    }

    @Test
    void strictAllowsWebhookSecretsWhenEnforced() {
        assertDoesNotThrow(() -> verify(new EdgeHardeningGuard(
                "https://app.lukeflow.com", "a-vapi-secret", /*vapiRequire=*/true,
                "a-nango-key", /*nangoRequire=*/true, /*requireStrong=*/true)));
    }
}
