package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards the fail-closed admin-password check (GHSA-8cj8): the app must refuse to
 * start in the prod ('postgres') profile with a blank or default 'admin' password,
 * while staying lenient (warn-only) in local/dev.
 */
class AdminPasswordGuardTest {

    private AdminPasswordGuard guard(String[] profiles, String password) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new AdminPasswordGuard(env, "admin", password);
    }

    @Test
    void prodProfile_defaultPassword_refusesToStart() {
        assertThrows(IllegalStateException.class,
                () -> guard(new String[] {"postgres"}, "admin").verify());
    }

    @Test
    void prodProfile_blankPassword_refusesToStart() {
        assertThrows(IllegalStateException.class,
                () -> guard(new String[] {"postgres"}, "").verify());
    }

    @Test
    void prodProfile_strongPassword_startsOk() {
        assertDoesNotThrow(() -> guard(new String[] {"postgres"}, "a-strong-secret-123").verify());
    }

    @Test
    void localProfile_defaultPassword_onlyWarns() {
        // No 'postgres' profile → local/dev → 'admin' is tolerated (warn only).
        assertDoesNotThrow(() -> guard(new String[] {}, "admin").verify());
    }
}
