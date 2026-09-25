package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** #58: dev-default secrets/embed/recipient keys must be a hard failure once strong keys are required. */
class InsecureKeyGuardTest {

    private static final String REAL_RECIPIENT = "a-real-recipient-secret";
    private static final String REAL_PORTAL = "a-real-portal-secret";
    private static final String REAL_PORTAL_TENANT = "a-real-portal-tenant-secret";

    @Test
    void devDefaultsWithStrictFailFast() {
        InsecureKeyGuard g = new InsecureKeyGuard(
                InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS, REAL_RECIPIENT,
                REAL_PORTAL, REAL_PORTAL_TENANT, true);
        assertThrows(IllegalStateException.class, g::verify);
    }

    @Test
    void devDefaultsWithoutStrictOnlyWarns() {
        InsecureKeyGuard g = new InsecureKeyGuard(
                InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS, InsecureKeyGuard.DEV_RECIPIENT,
                InsecureKeyGuard.DEV_PORTAL, InsecureKeyGuard.DEV_PORTAL_TENANT, false);
        assertDoesNotThrow(g::verify);
    }

    @Test
    void realKeysBootEvenUnderStrict() {
        InsecureKeyGuard g = new InsecureKeyGuard(
                "a-real-embed-secret", "a-real-master-key", REAL_RECIPIENT,
                REAL_PORTAL, REAL_PORTAL_TENANT, true);
        assertDoesNotThrow(g::verify);
    }

    @Test
    void strictFailsIfAnyKeyIsDefault() {
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard("real", InsecureKeyGuard.DEV_SECRETS, REAL_RECIPIENT, REAL_PORTAL, REAL_PORTAL_TENANT, true).verify());
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard(InsecureKeyGuard.DEV_EMBED, "real", REAL_RECIPIENT, REAL_PORTAL, REAL_PORTAL_TENANT, true).verify());
        // The recipient access-token secret (OTP-gated token signing) is now guarded too.
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard("real", "real", InsecureKeyGuard.DEV_RECIPIENT, REAL_PORTAL, REAL_PORTAL_TENANT, true).verify());
        // The two PORTAL secrets were added to the product long after this guard and were never
        // added to it: one signs the session minted after an OTP challenge, the other signs the
        // /portal/{token} link. Either at its dev default is forgeable from the public source.
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard("real", "real", REAL_RECIPIENT,
                        InsecureKeyGuard.DEV_PORTAL, REAL_PORTAL_TENANT, true).verify());
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard("real", "real", REAL_RECIPIENT,
                        REAL_PORTAL, InsecureKeyGuard.DEV_PORTAL_TENANT, true).verify());
    }

    @Test
    void prodProfileForcesStrictEvenWithFlagUnset() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("postgres", "prod");
        // Recipient default alone must fail-fast under prod (regression for the missing guard).
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard(
                        "real", "real", InsecureKeyGuard.DEV_RECIPIENT, REAL_PORTAL, REAL_PORTAL_TENANT, false, prod).verify());
    }

    @Test
    void postgresOnlyWithDevKeysStaysLenient() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("postgres");
        assertDoesNotThrow(
                () -> new InsecureKeyGuard(
                        InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS,
                        InsecureKeyGuard.DEV_RECIPIENT, InsecureKeyGuard.DEV_PORTAL,
                        InsecureKeyGuard.DEV_PORTAL_TENANT, false, dev).verify());
    }
}
