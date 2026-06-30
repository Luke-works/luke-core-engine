package com.luke.engine.capability.minion;

import com.luke.engine.config.RestTemplates;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * The {@code geocode} minion — a generic ADDRESS-AUTOCOMPLETE provider backed by Mapbox. As the user
 * types in an address field, this queries Mapbox's geocoding API SERVER-SIDE (the access token is a
 * server secret, never sent to the browser) and adapts each result into the normalized shape the
 * renderer expects: {@code { results: [ { id, label, address: { streetAddress, city, region, postalCode,
 * country, lat, lng } } ] } }.
 *
 * <p>Provider-agnostic by design: swapping Mapbox for Google/Loqate/etc. is a new minion class
 * returning the SAME shape — the form and renderer never change. Opted into the public/embed endpoint
 * ({@link #publicAllowed()}) so embedded forms can use it; the controller rate-limits per token/IP.
 *
 * <p>Config: set {@code MAPBOX_TOKEN} (env) — bound to {@code mapbox.token}. Absent → the minion
 * returns no results (the address field degrades to manual entry) and logs a one-line warning.
 */
@Component
public class GeocodeMinion implements Minion {

    private static final Logger log = LoggerFactory.getLogger(GeocodeMinion.class);
    private static final String MAPBOX_BASE = "https://api.mapbox.com/geocoding/v5/mapbox.places/";
    private static final int MAX_QUERY_LEN = 200;

    private final RestTemplate http = RestTemplates.withTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(5));

    @Value("${mapbox.token:}")
    private String mapboxToken;

    @Override
    public String name() {
        return "geocode";
    }

    @Override
    public boolean publicAllowed() {
        return true; // embedded forms need address autocomplete; the controller rate-limits it
    }

    @Override
    public Object handle(String tenantId, Map<String, Object> params) {
        Object raw = params.get("q");
        String q = raw == null ? "" : raw.toString().trim();
        if (q.isEmpty()) {
            return Map.of("results", List.of());
        }
        if (q.length() > MAX_QUERY_LEN) {
            q = q.substring(0, MAX_QUERY_LEN);
        }
        if (mapboxToken == null || mapboxToken.isBlank()) {
            log.warn("geocode minion called but mapbox.token (MAPBOX_TOKEN) is not configured — returning no results");
            return Map.of("results", List.of());
        }
        try {
            String encoded = URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20");
            // Build a URI and pass THAT (not a String) — RestTemplate re-encodes a String URL, which would
            // turn our %20 into %2520 and make Mapbox search for the literal "…%20…" → zero matches.
            URI uri = URI.create(MAPBOX_BASE + encoded + ".json?access_token=" + mapboxToken
                    + "&autocomplete=true&types=address&limit=5");
            Map<?, ?> body = http.getForObject(uri, Map.class);
            List<Map<String, Object>> results = mapFeatures(body);
            log.debug("geocode '{}' → {} result(s)", q, results.size());
            return Map.of("results", results);
        } catch (RuntimeException e) {
            // Never fail the user's keystroke — degrade to no suggestions and log.
            log.warn("geocode lookup failed: {}", e.toString());
            return Map.of("results", List.of());
        }
    }

    /**
     * Map a Mapbox geocoding response into normalized {@code { id, label, address }} suggestions.
     * Package-private + static so the adapter logic is unit-tested without a network call.
     */
    static List<Map<String, Object>> mapFeatures(Map<?, ?> body) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (body == null) return out;
        Object features = body.get("features");
        if (!(features instanceof List<?> list)) return out;
        for (Object f : list) {
            if (!(f instanceof Map<?, ?> feat)) continue;
            Map<String, Object> address = new LinkedHashMap<>();
            String street = str(feat.get("text"));
            String number = str(feat.get("address"));
            String streetAddress = number.isEmpty() ? street : (number + " " + street).trim();
            putIf(address, "streetAddress", streetAddress);
            // The structured parts come from Mapbox's `context` chain, keyed by id PREFIX.
            if (feat.get("context") instanceof List<?> ctx) {
                for (Object c : ctx) {
                    if (!(c instanceof Map<?, ?> cm)) continue;
                    String id = str(cm.get("id"));
                    String text = str(cm.get("text"));
                    if (text.isEmpty()) continue;
                    if (id.startsWith("place")) putIf(address, "city", text);
                    else if (id.startsWith("region")) putIf(address, "region", text);
                    else if (id.startsWith("postcode")) putIf(address, "postalCode", text);
                    else if (id.startsWith("country")) {
                        putIf(address, "country", text);
                        // Mapbox gives the ISO alpha-2 code as `short_code` — drives country-aware
                        // labels/validation in the renderer (more reliable than the display name).
                        String code = str(cm.get("short_code"));
                        if (!code.isEmpty()) address.put("countryCode", code.toUpperCase(Locale.ROOT));
                    }
                }
            }
            // center = [lng, lat]
            if (feat.get("center") instanceof List<?> center && center.size() == 2) {
                Double lng = num(center.get(0));
                Double lat = num(center.get(1));
                if (lng != null) address.put("lng", lng);
                if (lat != null) address.put("lat", lat);
            }
            String label = str(feat.get("place_name"));
            if (label.isEmpty()) label = streetAddress;
            if (label.isEmpty()) continue; // unusable without a label
            Map<String, Object> suggestion = new LinkedHashMap<>();
            String id = str(feat.get("id"));
            if (!id.isEmpty()) suggestion.put("id", id);
            suggestion.put("label", label);
            suggestion.put("address", address);
            out.add(suggestion);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static void putIf(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isEmpty()) m.put(key, value);
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? null : Double.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
