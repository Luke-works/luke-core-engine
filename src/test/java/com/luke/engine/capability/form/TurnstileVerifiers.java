package com.luke.engine.capability.form;

/**
 * Test builders for {@link TurnstileVerifier}, so suites that are not ABOUT the captcha can say so in
 * one word instead of repeating a six-argument constructor.
 *
 * <p>Everything here points at an unroutable URL: no test may depend on reaching Cloudflare, and a
 * verifier that quietly made a real network call would make the suite slow, flaky and dependent on the
 * machine having internet.
 */
final class TurnstileVerifiers {

    /** {@code 192.0.2.0/24} is TEST-NET-1 (RFC 5737) — reserved for documentation and guaranteed not to
     *  be routed, so a call here fails fast locally rather than escaping the machine. */
    private static final String UNROUTABLE = "http://192.0.2.1:9/siteverify";

    private TurnstileVerifiers() {}

    /** Captcha switched off — the gate is skipped entirely, as with {@code luke.embed.captcha.enabled=false}. */
    static TurnstileVerifier disabled() {
        return new TurnstileVerifier(false, TurnstileVerifier.DUMMY_SECRET_PASS,
                TurnstileVerifier.DUMMY_SITEKEY_PASS, 3000, UNROUTABLE, false);
    }

    /** Enabled, pointed at {@code verifyUrl} (normally a local stub), with the given fail-closed policy. */
    static TurnstileVerifier enabled(String verifyUrl, boolean failClosed) {
        return new TurnstileVerifier(true, TurnstileVerifier.DUMMY_SECRET_PASS,
                TurnstileVerifier.DUMMY_SITEKEY_PASS, 500, verifyUrl, failClosed);
    }

    /** Enabled and pointed at nothing reachable → every verification is UNREACHABLE. */
    static TurnstileVerifier unreachable(boolean failClosed) {
        return enabled(UNROUTABLE, failClosed);
    }
}
