package com.luke.engine.config;

import org.springframework.core.env.Environment;

/**
 * The dedicated {@code prod} Spring profile is the single switch that flips every
 * opt-in security guard ({@link AuthHardeningGuard} #56, {@link InsecureKeyGuard} #58)
 * into fail-fast mode.
 *
 * <p>It is INTENTIONALLY distinct from the {@code postgres} profile. dev/qa run
 * {@code postgres} (and do NOT set the {@code sync:false} operator credential, gateway
 * JWKS, internal shared secret, or real crypto keys), so keying these fail-fast guards
 * off {@code postgres} would crash dev/qa on every deploy. Prod runs
 * {@code SPRING_PROFILES_ACTIVE=postgres,prod} once its secrets are configured — at
 * which point any remaining fail-open auth layer or dev-default key refuses startup.
 *
 * <p>Each guard still also honors its individual opt-in flag
 * ({@code luke.auth.require-strong-auth} / {@code luke.security.require-strong-keys})
 * so strictness can be exercised without the full profile (e.g. in tests).
 *
 * <p>PUBLIC because the rule now has a second consumer outside this package:
 * {@link com.luke.engine.capability.form.TurnstileVerifier} fails CLOSED on an unreachable
 * Cloudflare only under this same profile. One definition of "strict", not two.
 */
public final class StrictProfile {

    public static final String PROFILE = "prod";

    private StrictProfile() {}

    /** True when the {@code prod} Spring profile is active. */
    public static boolean isActive(Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String p : environment.getActiveProfiles()) {
            if (PROFILE.equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }
}
