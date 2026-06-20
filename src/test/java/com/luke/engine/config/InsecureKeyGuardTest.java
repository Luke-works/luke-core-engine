package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** #58: dev-default secrets/embed keys must be a hard failure once strong keys are required. */
class InsecureKeyGuardTest {

    @Test
    void devDefaultsWithStrictFailFast() {
        InsecureKeyGuard g = new InsecureKeyGuard(
                InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS, true);
        assertThrows(IllegalStateException.class, g::verify);
    }

    @Test
    void devDefaultsWithoutStrictOnlyWarns() {
        InsecureKeyGuard g = new InsecureKeyGuard(
                InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS, false);
        assertDoesNotThrow(g::verify);
    }

    @Test
    void realKeysBootEvenUnderStrict() {
        InsecureKeyGuard g = new InsecureKeyGuard("a-real-embed-secret", "a-real-master-key", true);
        assertDoesNotThrow(g::verify);
    }

    @Test
    void strictFailsIfEitherKeyIsDefault() {
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard("real", InsecureKeyGuard.DEV_SECRETS, true).verify());
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard(InsecureKeyGuard.DEV_EMBED, "real", true).verify());
    }

    @Test
    void prodProfileForcesStrictEvenWithFlagUnset() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("postgres", "prod");
        assertThrows(IllegalStateException.class,
                () -> new InsecureKeyGuard(
                        InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS, false, prod).verify());
    }

    @Test
    void postgresOnlyWithDevKeysStaysLenient() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("postgres");
        assertDoesNotThrow(
                () -> new InsecureKeyGuard(
                        InsecureKeyGuard.DEV_EMBED, InsecureKeyGuard.DEV_SECRETS, false, dev).verify());
    }
}
