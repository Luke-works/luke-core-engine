package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Labels are decoration anchored to the allowlist. These tests pin the two properties that make that
 * safe: a label can never appear for an origin the browser isn't allowing, and unreadable labels
 * never stop a form working.
 */
class EmbedSiteNamesTest {

    private static Map<String, String> names(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    @DisplayName("keeps labels for allowed origins and round-trips them")
    void roundTrip() {
        String json = EmbedSiteNames.toJson(
                names("https://acme.com", "Acme main site", "https://shop.acme.com", "Shop"),
                "https://acme.com,https://shop.acme.com");
        assertThat(EmbedSiteNames.fromJson(json))
                .containsEntry("https://acme.com", "Acme main site")
                .containsEntry("https://shop.acme.com", "Shop");
    }

    @Test
    @DisplayName("drops a label whose origin is NOT in the allowlist")
    void dropsUnallowed() {
        // Otherwise a client could use this column as unbounded storage, and removing a site would
        // leave its name behind describing an origin nobody may frame from.
        String json = EmbedSiteNames.toJson(
                names("https://acme.com", "Acme", "https://evil.test", "Not allowed here"),
                "https://acme.com");
        assertThat(EmbedSiteNames.fromJson(json)).containsOnlyKeys("https://acme.com");
    }

    @Test
    @DisplayName("matches the CANONICAL origin, so casing and a trailing slash still line up")
    void canonicalises() {
        String json = EmbedSiteNames.toJson(names("HTTPS://Acme.com/", "Acme"), "https://acme.com");
        assertThat(EmbedSiteNames.fromJson(json)).containsEntry("https://acme.com", "Acme");
    }

    @Test
    @DisplayName("an empty label removes the entry rather than storing a blank")
    void blankRemoves() {
        assertThat(EmbedSiteNames.toJson(names("https://acme.com", "   "), "https://acme.com")).isNull();
    }

    @Test
    @DisplayName("truncates an over-long label instead of failing the save")
    void truncates() {
        String json = EmbedSiteNames.toJson(names("https://acme.com", "x".repeat(500)), "https://acme.com");
        assertThat(EmbedSiteNames.fromJson(json).get("https://acme.com")).hasSize(EmbedSiteNames.MAX_NAME);
    }

    @Test
    @DisplayName("no allowlist means no labels — nothing to anchor them to")
    void noAllowlist() {
        assertThat(EmbedSiteNames.toJson(names("https://acme.com", "Acme"), null)).isNull();
        assertThat(EmbedSiteNames.toJson(names("https://acme.com", "Acme"), "")).isNull();
    }

    @Test
    @DisplayName("unreadable stored JSON reads as no labels, never an exception")
    void unreadableIsEmpty() {
        // The allowlist is a separate column, so a corrupted label blob must not take the form down.
        assertThat(EmbedSiteNames.fromJson("{not json")).isEmpty();
        assertThat(EmbedSiteNames.fromJson("[\"an\",\"array\"]")).isEmpty();
        assertThat(EmbedSiteNames.fromJson("{\"https://a.com\": 42}")).isEmpty();
        assertThat(EmbedSiteNames.fromJson(null)).isEmpty();
    }

    @Test
    @DisplayName("null/blank entries are skipped, not stored")
    void skipsNulls() {
        Map<String, String> m = names("https://acme.com", "Acme");
        m.put("https://b.com", null);
        m.put(null, "orphan");
        String json = EmbedSiteNames.toJson(m, "https://acme.com,https://b.com");
        assertThat(EmbedSiteNames.fromJson(json)).containsOnlyKeys("https://acme.com");
    }
}
