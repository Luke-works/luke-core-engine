package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The dedicated 'prod' profile is the single fail-fast switch (#56, #58). */
class StrictProfileTest {

    @Test
    void postgresAloneIsNotStrict() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("postgres");
        assertFalse(StrictProfile.isActive(env), "dev/qa run postgres only and must NOT fail-fast");
    }

    @Test
    void prodAmongProfilesIsStrict() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("postgres", "prod");
        assertTrue(StrictProfile.isActive(env));
    }

    @Test
    void noProfilesIsNotStrict() {
        assertFalse(StrictProfile.isActive(new MockEnvironment()));
        assertFalse(StrictProfile.isActive(null));
    }
}
