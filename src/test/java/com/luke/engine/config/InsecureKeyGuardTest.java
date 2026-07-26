package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** #58: dev-default secrets/embed/recipient keys must be a hard failure once strong keys are required. */
class InsecureKeyGuardTest {

    private static final String REAL_RECIPIENT = "a-real-recipient-secret";

    @Test
    void devDefaultsWithStrictFailFast() {
        InsecureKeyGuard g = new InsecureKeyGuard(
                InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS, REAL_RECIPIENT, true);
        assertThrows(IllegalStateException.class, g::verify);
    }

    @Test
    void devDefaultsWithoutStrictOnlyWarns() {
        InsecureKeyGuard g = new InsecureKeyGuard(
                InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS, InsecureKeyGuard.DEV_RECIPIENT, false);
        assertDoesNotThrow(g::verify);
    }

    @Test
    void realKeysBootEvenUnderStrict() {
        InsecureKeyGuard g = new InsecureKeyGuard(
                "a-real-embed-secret", "a-real-master-key", REAL_RECIPIENT, true);
        assertDoesNotThrow(g::verify);
    }

    @Test
    void strictFailsIfAnyKeyIsDefault() {
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard("real", InsecureKeyGuard.DEV_SECRETS, REAL_RECIPIENT, true).verify());
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard(InsecureKeyGuard.DEV_EMBED, "real", REAL_RECIPIENT, true).verify());
        // The recipient access-token secret (OTP-gated token signing) is now guarded too.
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard("real", "real", InsecureKeyGuard.DEV_RECIPIENT, true).verify());
    }

    @Test
    void prodProfileForcesStrictEvenWithFlagUnset() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("postgres", "prod");
        // Recipient default alone must fail-fast under prod (regression for the missing guard).
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard(
                        "real", "real", InsecureKeyGuard.DEV_RECIPIENT, false, prod).verify());
    }

    @Test
    void postgresOnlyWithDevKeysStaysLenient() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("postgres");
        assertDoesNotThrow(
                () -> new InsecureKeyGuard(
                        InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS,
                        InsecureKeyGuard.DEV_RECIPIENT, false, dev).verify());
    }
}
