package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure unit tests for the embed allowlist → CSP frame-ancestors policy (no Spring / DB). */
class FrameAncestorsTest {

    @Test
    void emptyOrNullAllowlistIsPublic() {
        assertThat(FrameAncestors.directive(null)).isEqualTo("*");
        assertThat(FrameAncestors.directive("")).isEqualTo("*");
        assertThat(FrameAncestors.directive("   ")).isEqualTo("*");
        assertThat(FrameAncestors.normalizeList(null)).isNull();
        assertThat(FrameAncestors.normalizeList("  ")).isNull();
    }

    @Test
    void keepsWellFormedOriginsAndRendersSpaceSeparatedDirective() {
        String csv = FrameAncestors.normalizeList("https://acme.com, https://*.acme.com:8443");
        assertThat(csv).isEqualTo("https://acme.com,https://*.acme.com:8443");
        assertThat(FrameAncestors.directive("https://acme.com https://*.acme.com:8443"))
                .isEqualTo("https://acme.com https://*.acme.com:8443");
    }

    @Test
    void tolerantSplittingAcrossCommasWhitespaceAndNewlines() {
        assertThat(FrameAncestors.normalizeList("https://a.com\n https://b.com,https://c.com"))
                .isEqualTo("https://a.com,https://b.com,https://c.com");
    }

    @Test
    void lowercasesDedupesAndTrimsTrailingSlash() {
        assertThat(FrameAncestors.normalizeList("HTTPS://Acme.com/, https://acme.com"))
                .isEqualTo("https://acme.com");
    }

    @Test
    void isAllInvalidDistinguishesClearedFromMistyped() {
        // Cleared / blank → intentional public default (NOT all-invalid → allowed through to "*").
        assertThat(FrameAncestors.isAllInvalid(null)).isFalse();
        assertThat(FrameAncestors.isAllInvalid("")).isFalse();
        assertThat(FrameAncestors.isAllInvalid("   ")).isFalse();
        // Typed but nothing parses → config mistake → fail closed (caller rejects, never widens to "*").
        assertThat(FrameAncestors.isAllInvalid("example.com")).isTrue(); // no scheme
        assertThat(FrameAncestors.isAllInvalid("*.evil, junk")).isTrue();
        assertThat(FrameAncestors.isAllInvalid("https://ok.com'; script-src *")).isTrue();
        // At least one valid origin → accepted (junk dropped, the rest enforced).
        assertThat(FrameAncestors.isAllInvalid("https://ok.com, junk")).isFalse();
    }

    @Test
    void dropsMalformedEntriesSoTheyCannotWidenOrInjectTheDirective() {
        // path, no-scheme, javascript:, a bare '*', a quote-injection attempt, and a space-token all dropped.
        assertThat(FrameAncestors.normalizeList("https://ok.com/login")).isNull();
        assertThat(FrameAncestors.normalizeList("acme.com")).isNull();
        assertThat(FrameAncestors.normalizeList("javascript:alert(1)")).isNull();
        assertThat(FrameAncestors.normalizeList("*")).isNull();
        assertThat(FrameAncestors.normalizeList("https://ok.com' 'none")).isNull();
        // a valid origin alongside junk keeps only the valid one
        assertThat(FrameAncestors.normalizeList("not-an-origin, https://good.com"))
                .isEqualTo("https://good.com");
    }
}
