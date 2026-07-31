package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-language parity — the core-engine half.
 *
 * <p>The declarative validation rules exist twice: in {@code @lukeflow/form-core} (TypeScript, runs
 * in the filler's browser) and in {@link SubmissionRules} (Java, the backstop a tampered payload
 * cannot skip). {@code validation-parity.json} is the shared case table; the form-core repo runs it
 * in {@code parity.test.ts} and this runs the same cases here. A divergence fails one of the two.
 *
 * <p><b>Keeping the copies in step:</b> the fixture is authored in {@code luke-forms/fixtures/} and
 * copied to {@code src/test/resources/}. The two repos share no CI, so nothing can automatically
 * prove the copies match — {@link #EXPECTED_REVISION} is the tripwire: changing the rules means
 * bumping {@code revision} in the fixture, which fails here until a human updates this constant and
 * re-copies. That's a review-time catch, not a build-time one, and is the acknowledged limit of
 * two-implementation parity.
 */
class SubmissionValidatorParityTest {

    /**
     * Pinned fixture revision. Bump ONLY together with re-copying the fixture from luke-forms and
     * confirming {@code parity.test.ts} passes there.
     */
    private static final String EXPECTED_REVISION = "2026-07-31.2";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode FIXTURE = load();

    private static JsonNode load() {
        try (InputStream in = SubmissionValidatorParityTest.class.getResourceAsStream("/validation-parity.json")) {
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("validation-parity.json is missing from test resources", e);
        }
    }

    @Test
    void fixtureIsTheRevisionThisImplementationWasWrittenAgainst() {
        assertThat(FIXTURE.path("revision").asText())
                .as("fixture revision drifted — re-copy from luke-forms and re-verify both languages")
                .isEqualTo(EXPECTED_REVISION);
        assertThat(FIXTURE.path("cases").size()).isGreaterThan(40);
    }

    @Test
    void caseIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (JsonNode c : FIXTURE.path("cases")) assertThat(seen.add(c.path("id").asText())).isTrue();
        for (JsonNode c : FIXTURE.path("serverOnlyCases")) assertThat(seen.add(c.path("id").asText())).isTrue();
    }

    static Stream<Arguments> sharedCases() {
        return cases("cases");
    }

    static Stream<Arguments> serverOnlyCases() {
        return cases("serverOnlyCases");
    }

    private static Stream<Arguments> cases(String field) {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode c : FIXTURE.path(field)) out.add(Arguments.of(c.path("id").asText(), c));
        return out.stream();
    }

    /** Rules that also exist in form-core — these are the parity contract. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedCases")
    void matchesFormCore(String id, JsonNode testCase) {
        assertCase(id, testCase);
    }

    /**
     * Rules the server enforces that form-core does NOT. Option-membership used to live here; the
     * client now implements it with the same semantics, so those cases moved into the shared set
     * and this list is empty. A plain loop rather than a {@code @ParameterizedTest}, which errors
     * on an empty source — the harness stays so a genuinely server-only rule can be added back.
     */
    @Test
    void enforcesServerOnlyRules() {
        for (JsonNode c : FIXTURE.path("serverOnlyCases")) {
            assertCase(c.path("id").asText(), c);
        }
    }

    private void assertCase(String id, JsonNode testCase) {
        FormSupport.FieldRule field = new FormSupport.FieldRule(
                "f",
                testCase.path("type").asText(""),
                testCase.path("attributes").path("required").asBoolean(false),
                false,
                testCase.path("attributes"));

        // An ABSENT `value` key means undefined; Jackson gives a missing node, which converts to null.
        Object value = MAPPER.convertValue(testCase.get("value"), Object.class);

        boolean expectedValid = testCase.path("valid").asBoolean();
        // `required` is evaluated separately from the value-shape rules (it depends on whether the
        // server may hard-enforce it), so compose the two here exactly as SubmissionValidator does.
        boolean requiredOk = !field.required() || SubmissionRules.satisfiesRequired(field, value);
        String failureCode = requiredOk ? SubmissionRules.firstFailure(field, value) : "required";

        assertThat(failureCode == null)
                .as("%s — expected %s, got failure code %s", id, expectedValid ? "valid" : "invalid", failureCode)
                .isEqualTo(expectedValid);

        if (!expectedValid && testCase.hasNonNull("code")) {
            assertThat(failureCode).as("%s — failure code", id).isEqualTo(testCase.path("code").asText());
        }
    }
}
