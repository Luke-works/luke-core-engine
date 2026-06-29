package com.luke.engine.capability.minion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Mapbox→normalized adapter is the only non-trivial logic in the geocode minion (the HTTP call is
 *  a thin wrapper). Verify it shapes a real-world feature into the renderer's contract. */
class GeocodeMinionTest {

    private static Map<String, Object> feature() {
        return Map.of(
                "id", "address.123",
                "place_name", "221B Baker Street, London NW1 6XE, United Kingdom",
                "text", "Baker Street",
                "address", "221B",
                "center", List.of(-0.1582, 51.5237),
                "context", List.of(
                        Map.of("id", "postcode.1", "text", "NW1 6XE"),
                        Map.of("id", "place.2", "text", "London"),
                        Map.of("id", "region.3", "text", "England"),
                        Map.of("id", "country.4", "text", "United Kingdom", "short_code", "gb")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsAMapboxFeatureIntoTheNormalizedSuggestionShape() {
        List<Map<String, Object>> out = GeocodeMinion.mapFeatures(Map.of("features", List.of(feature())));
        assertThat(out).hasSize(1);
        Map<String, Object> s = out.get(0);
        assertThat(s.get("id")).isEqualTo("address.123");
        assertThat(s.get("label")).isEqualTo("221B Baker Street, London NW1 6XE, United Kingdom");
        Map<String, Object> addr = (Map<String, Object>) s.get("address");
        assertThat(addr.get("line1")).isEqualTo("221B Baker Street");
        assertThat(addr.get("city")).isEqualTo("London");
        assertThat(addr.get("region")).isEqualTo("England");
        assertThat(addr.get("postalCode")).isEqualTo("NW1 6XE");
        assertThat(addr.get("country")).isEqualTo("United Kingdom");
        assertThat(addr.get("countryCode")).isEqualTo("GB"); // from Mapbox short_code, upper-cased
        assertThat(addr.get("lat")).isEqualTo(51.5237);
        assertThat(addr.get("lng")).isEqualTo(-0.1582);
    }

    @Test
    void toleratesMissingPiecesAndJunk() {
        assertThat(GeocodeMinion.mapFeatures(null)).isEmpty();
        assertThat(GeocodeMinion.mapFeatures(Map.of())).isEmpty();
        assertThat(GeocodeMinion.mapFeatures(Map.of("features", "nope"))).isEmpty();
        // A feature with no place_name falls back to line1 for its label; a label-less, line1-less one is dropped.
        List<Map<String, Object>> out = GeocodeMinion.mapFeatures(Map.of("features", List.of(
                Map.of("text", "Main St", "address", "10"),
                Map.of("center", List.of(1, 2)))));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("label")).isEqualTo("10 Main St");
    }
}
