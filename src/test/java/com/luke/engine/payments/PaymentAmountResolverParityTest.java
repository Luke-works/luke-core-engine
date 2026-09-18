package com.luke.engine.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-language parity — the core-engine half of {@code payment-parity.json}.
 *
 * <p>Payment amount resolution exists twice: in {@code @lukeflow/form-core} (the preview a payer sees)
 * and in {@link PaymentAmountResolver} (what the card is actually charged). The fixture is authored in
 * {@code luke-forms/fixtures/} and copied to {@code src/test/resources/}; {@code parity.test.ts} runs
 * the same cases there. {@link #EXPECTED_REVISION} is the tripwire for a change made in one language
 * only — bump it only after re-copying the fixture and seeing both suites pass.
 */
class PaymentAmountResolverParityTest {

    private static final String EXPECTED_REVISION = "2026-09-17.2";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode FIXTURE = load();

    private static JsonNode load() {
        try (InputStream in = PaymentAmountResolverParityTest.class.getResourceAsStream("/payment-parity.json")) {
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("payment-parity.json is missing from test resources", e);
        }
    }

    @Test
    void fixtureIsTheRevisionThisImplementationWasWrittenAgainst() {
        assertThat(FIXTURE.path("revision").asText())
                .as("fixture revision drifted — re-copy from luke-forms and re-verify both languages")
                .isEqualTo(EXPECTED_REVISION);
        assertThat(FIXTURE.path("cases").size()).isGreaterThan(160);
        Set<String> ids = new HashSet<>();
        FIXTURE.path("cases").forEach(c -> assertThat(ids.add(c.path("id").asText())).as("unique id").isTrue());
    }

    @Test
    void currencyTableMatchesFormCore() {
        List<String> expected = new ArrayList<>();
        FIXTURE.path("currencies").forEach(c -> expected.add(
                c.path("code").asText() + ":" + c.path("exponent").asInt() + ":" + c.path("minimumMinor").asLong()));
        List<String> actual = Currencies.codes().stream()
                .map(code -> Currencies.of(code).orElseThrow())
                .map(i -> i.code() + ":" + i.exponent() + ":" + i.minimumMinor())
                .toList();
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        FIXTURE.path("cases").forEach(c -> out.add(Arguments.of(c.path("id").asText(), c)));
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void resolvesExactlyLikeFormCore(String id, JsonNode c) throws Exception {
        String schema = MAPPER.writeValueAsString(schemaFor(c));
        // The production path: the request body arrives as JSON and Jackson maps it (numbers become
        // Integer/Long/Double exactly as they do for a real submission).
        Map<String, Object> data = MAPPER.convertValue(c.path("data"), new TypeReference<>() {});

        PaymentAmountResolver.Resolution r = PaymentAmountResolver.resolve(schema, data);
        JsonNode expect = c.path("expect");

        assertThat(r.ok()).as(id + " ok").isEqualTo(expect.path("ok").asBoolean());
        if (r.ok()) {
            if (expect.has("amountMinor")) assertThat(r.amountMinor()).as(id + " amount").isEqualTo(expect.path("amountMinor").asLong());
            if (expect.has("currency")) assertThat(r.currency()).as(id + " currency").isEqualTo(expect.path("currency").asText());
            if (expect.has("mode")) assertThat(r.mode()).as(id + " mode").isEqualTo(expect.path("mode").asText());
            Integer expectedQty = expect.has("quantity") ? expect.path("quantity").asInt() : null;
            assertThat(r.quantity()).as(id + " quantity").isEqualTo(expectedQty);
        } else {
            assertThat(r.failure().code()).as(id + " reason").isEqualTo(expect.path("reason").asText());
        }
    }

    /** A children/root entry as a key: strings as-is, non-negative integers as text (the fixture note). */
    private static String keyOf(JsonNode v) {
        if (v.isTextual()) return v.textValue();
        if (v.isIntegralNumber() && v.asLong() >= 0) return String.valueOf(v.asLong());
        return null;
    }

    /** Same construction as parity.test.ts — see the fixture's note. */
    private static ObjectNode schemaFor(JsonNode c) {
        ObjectNode schema = MAPPER.createObjectNode();
        List<Map.Entry<String, ObjectNode>> ordered = new ArrayList<>();
        List<String> root = new ArrayList<>();
        JsonNode payment = c.get("payment");
        String payIn = c.hasNonNull("payIn") ? c.path("payIn").asText() : null;
        if (payment != null && !payment.isNull()) {
            ObjectNode pay = MAPPER.createObjectNode();
            pay.put("id", "pay");
            pay.put("type", "payment");
            ObjectNode attrs = pay.putObject("attributes");
            attrs.put("key", "pay");
            attrs.setAll((ObjectNode) payment);
            ordered.add(Map.entry("pay", pay));
            if (payIn == null) root.add("pay");
        }
        if (c.hasNonNull("use")) {
            JsonNode set = FIXTURE.path("sets").path(c.path("use").asText());
            assertThat(set.isObject()).as("unknown set " + c.path("use").asText()).isTrue();
            ObjectNode copy = set.deepCopy();
            TreeSet<String> ids = new TreeSet<>();
            copy.fieldNames().forEachRemaining(ids::add);
            Set<String> listed = new HashSet<>();
            ids.forEach(id -> copy.path(id).path("children").forEach(k -> {
                String key = keyOf(k);
                if (key != null) listed.add(key);
            }));
            if (payIn != null && !ordered.isEmpty()) {
                assertThat(copy.path(payIn).isObject()).as("unknown payIn " + payIn).isTrue();
                JsonNode kids = copy.path(payIn).path("children");
                ArrayNode children = kids.isArray() ? (ArrayNode) kids : ((ObjectNode) copy.get(payIn)).putArray("children");
                children.add("pay");
            }
            ids.stream().filter(id -> !listed.contains(id)).forEach(root::add);
            ids.forEach(id -> ordered.add(Map.entry(id, (ObjectNode) copy.get(id))));
        }
        Set<String> omit = new HashSet<>();
        c.path("omitFromRoot").forEach(o -> omit.add(o.asText()));
        root.removeIf(omit::contains);
        if (c.path("entitiesAsArray").asBoolean(false)) {
            if (!c.path("arrayKeepIds").asBoolean(false)) {
                Map<String, String> pos = new java.util.HashMap<>();
                for (int i = 0; i < ordered.size(); i++) pos.put(ordered.get(i).getKey(), String.valueOf(i));
                for (Map.Entry<String, ObjectNode> e : ordered) {
                    JsonNode kids = e.getValue().get("children");
                    if (kids == null || !kids.isArray()) continue;
                    ArrayNode remapped = MAPPER.createArrayNode();
                    kids.forEach(k -> {
                        String key = keyOf(k);
                        if (key != null && pos.containsKey(key)) remapped.add(pos.get(key));
                        else remapped.add(k);
                    });
                    e.getValue().set("children", remapped);
                }
                root.replaceAll(id -> pos.getOrDefault(id, id));
            }
            ArrayNode entities = schema.putArray("entities");
            ordered.forEach(e -> entities.add(e.getValue()));
        } else {
            ObjectNode entities = schema.putObject("entities");
            ordered.forEach(e -> entities.set(e.getKey(), e.getValue()));
        }
        ArrayNode rootOut = schema.putArray("root");
        boolean numeric = c.path("numericRoot").asBoolean(false);
        root.forEach(id -> {
            if (numeric && id.matches("\\d+")) rootOut.add(Long.parseLong(id));
            else rootOut.add(id);
        });
        return schema;
    }
}
