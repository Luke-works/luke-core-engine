package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** #34: the H2 console (unauthenticated SQL shell) must never boot under the prod profile. */
class H2ConsoleGuardTest {

    @Test
    void consoleEnabledUnderPostgresProfileFailsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("postgres");
        assertThrows(IllegalStateException.class, () -> new H2ConsoleGuard(env, true).verify());
    }

    @Test
    void consoleEnabledInLocalDevIsAllowed() {
        assertDoesNotThrow(() -> new H2ConsoleGuard(new MockEnvironment(), true).verify());
    }

    @Test
    void consoleDisabledBootsUnderAnyProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("postgres");
        assertDoesNotThrow(() -> new H2ConsoleGuard(env, false).verify());
    }
}
