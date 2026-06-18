package com.luke.engine.capability.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrgDomainMatcher}: org-name normalization, registrable
 * domain-root extraction, free-provider detection, the lenient overlap match, and
 * the light email-shape check.
 */
class OrgDomainMatcherTest {

    @Test
    void normalizeNameStripsCorporateNoise() {
        assertThat(OrgDomainMatcher.normalizeName("The Acme Corporation")).isEqualTo("acme");
        assertThat(OrgDomainMatcher.normalizeName("Acme Inc.")).isEqualTo("acme");
        assertThat(OrgDomainMatcher.normalizeName("ACME, LLC")).isEqualTo("acme");
    }

    @Test
    void normalizeNameFallsBackWhenEveryTokenIsNoise() {
        // "tech" and "labs" are both noise words; rather than empty, keep the joined name.
        assertThat(OrgDomainMatcher.normalizeName("Tech Labs")).isEqualTo("techlabs");
    }

    @Test
    void domainRootHandlesPlainAndTwoLevelTlds() {
        assertThat(OrgDomainMatcher.domainRoot("acme.com")).isEqualTo("acme");
        assertThat(OrgDomainMatcher.domainRoot("mail.acme.co.uk")).isEqualTo("acme");
        assertThat(OrgDomainMatcher.domainRoot("acme.co.uk")).isEqualTo("acme");
    }

    @Test
    void freeProvidersAreDetected() {
        assertThat(OrgDomainMatcher.isFreeProvider("gmail.com")).isTrue();
        assertThat(OrgDomainMatcher.isFreeProvider("outlook.com")).isTrue();
        assertThat(OrgDomainMatcher.isFreeProvider("acme.com")).isFalse();
    }

    @Test
    void lenientMatchAcceptsOverlap() {
        assertThat(OrgDomainMatcher.match("Acme Corporation", "acme.com").ok()).isTrue();
        assertThat(OrgDomainMatcher.match("Acme", "acmecorp.com").ok()).isTrue();   // root contains name
        assertThat(OrgDomainMatcher.match("Acme Labs", "acme.io").ok()).isTrue();   // name contains root
    }

    @Test
    void lenientMatchRejectsUnrelated() {
        assertThat(OrgDomainMatcher.match("Acme", "randomstartup.com").ok()).isFalse();
        assertThat(OrgDomainMatcher.match("", "acme.com").ok()).isFalse();
    }

    @Test
    void domainOfExtractsLowercasedDomain() {
        assertThat(OrgDomainMatcher.domainOf("Jane@Acme.COM")).isEqualTo("acme.com");
        assertThat(OrgDomainMatcher.domainOf("no-at-sign")).isEmpty();
    }

    @Test
    void looksLikeEmailChecksShape() {
        assertThat(OrgDomainMatcher.looksLikeEmail("jane@acme.com")).isTrue();
        assertThat(OrgDomainMatcher.looksLikeEmail("jane@localhost")).isFalse(); // no dotted domain
        assertThat(OrgDomainMatcher.looksLikeEmail("jane @acme.com")).isFalse(); // whitespace
        assertThat(OrgDomainMatcher.looksLikeEmail("two@@acme.com")).isFalse();
    }
}
