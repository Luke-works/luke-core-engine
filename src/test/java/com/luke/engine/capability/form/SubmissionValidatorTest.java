package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Server-side submission validation/cleaning (Route B M3) — pure unit tests, no Spring/DB. */
class SubmissionValidatorTest {

    private static final String NUL = String.valueOf((char) 0);
    private static final String BEL = String.valueOf((char) 7);
    private static final String TAB = String.valueOf((char) 9);

    // coltorapps-shaped schema: fullName (required), age, notes.
    private static final String SCHEMA = """
            {"entities":{
              "e1":{"type":"text","attributes":{"key":"fullName","required":true}},
              "e2":{"type":"number","attributes":{"key":"age"}},
              "e3":{"type":"text","attributes":{"key":"notes"}}
            }}""";

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void stripsUnknownFieldsAndKeepsDeclaredOnes() {
        Map<String, Object> out = SubmissionValidator.clean(SCHEMA, map("fullName", "Ada", "age", 30, "evil", "injected"));
        assertThat(out).containsOnlyKeys("fullName", "age");
    }

    @Test
    void enforcesRequiredFields() {
        assertThatThrownBy(() -> SubmissionValidator.clean(SCHEMA, map("age", 30)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fullName");
        assertThatThrownBy(() -> SubmissionValidator.clean(SCHEMA, map("fullName", "")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void whitespaceOnlyCountsAsProvided_matchingTheRenderer() {
        // CHANGED, deliberately: this used to 400. The renderer accepts "   " in a required field,
        // so rejecting it server-side meant a submission the user was told was fine came back as an
        // error with nothing to fix. The parity fixture pins this to form-core's isEmptyValue.
        assertThat(SubmissionValidator.clean(SCHEMA, map("fullName", "   "))).containsKey("fullName");
    }

    @Test
    void requiredIsMoreThanNotBlank() {
        // The flip side of aligning with form-core: three cases the old not-blank check let through.
        String schema = """
                {"entities":{
                  "e1":{"type":"checkbox","attributes":{"key":"agree","required":true}}
                }}""";
        // An unchecked consent box is MISSING, not merely false — this previously passed.
        assertThatThrownBy(() -> SubmissionValidator.clean(schema, map("agree", false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("agree");
        assertThat(SubmissionValidator.clean(schema, map("agree", true))).containsEntry("agree", true);

        String matrixSchema = """
                {"entities":{
                  "e1":{"type":"matrix","attributes":{"key":"answers","required":true}}
                }}""";
        // An object whose every answer is blank is an empty answer set, not a provided one.
        assertThatThrownBy(() -> SubmissionValidator.clean(matrixSchema, map("answers", map("r1", "", "r2", ""))))
                .isInstanceOf(ResponseStatusException.class);

        String addressSchema = """
                {"entities":{
                  "e1":{"type":"addressBlock","attributes":{"key":"addr","required":true}}
                }}""";
        // A street-only address is non-empty but incomplete.
        assertThatThrownBy(() -> SubmissionValidator.clean(addressSchema, map("addr", map("streetAddress", "1 Main St"))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void enforcesDeclarativeValueRules() {
        String schema = """
                {"entities":{
                  "e1":{"type":"email","attributes":{"key":"email"}},
                  "e2":{"type":"text","attributes":{"key":"code","pattern":"^[0-9]{3}$"}},
                  "e3":{"type":"select","attributes":{"key":"size","options":["S","M","L"]}}
                }}""";
        // Rules the renderer applies but a hand-rolled POST never would.
        assertThatThrownBy(() -> SubmissionValidator.clean(schema, map("email", "banana")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("email");
        assertThatThrownBy(() -> SubmissionValidator.clean(schema, map("code", "12a")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("pattern");
        assertThatThrownBy(() -> SubmissionValidator.clean(schema, map("size", "Gigantic")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("option");
        // …and a well-formed submission still sails through.
        assertThat(SubmissionValidator.clean(schema, map("email", "ada@example.com", "code", "123", "size", "M")))
                .containsOnlyKeys("email", "code", "size");
    }

    @Test
    void conditionallyHiddenFieldWithACountBoundDoesNotRejectTheSubmission() {
        // REGRESSION: count rules read an absent value as "zero items", so a hidden field carrying
        // minFiles/minRows/minSelected would 400 a submission the renderer considered complete —
        // it never showed the field, and collect() omits it. Same false-400 that conditionally
        // -hidden REQUIRED fields once caused on embed submit.
        String schema = """
                {"entities":{
                  "e1":{"type":"text","attributes":{"key":"name"}},
                  "e2":{"type":"file","attributes":{"key":"docs","minFiles":1,"customConditional":"name == 'x'"}}
                }}""";
        assertThat(SubmissionValidator.clean(schema, map("name", "Ada"))).containsOnlyKeys("name");

        // …but when the field DID arrive, it is still held to what the author declared, so a value
        // smuggled into a conditional field can't dodge validation.
        String emailSchema = """
                {"entities":{
                  "e1":{"type":"text","attributes":{"key":"name"}},
                  "e2":{"type":"email","attributes":{"key":"alt","customConditional":"name == 'x'"}}
                }}""";
        assertThatThrownBy(() -> SubmissionValidator.clean(emailSchema, map("name", "Ada", "alt", "banana")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("alt");
    }

    @Test
    void draftModeSkipsValueRulesToo() {
        // A half-typed email is the normal state of an autosave; only submit judges shape.
        String schema = """
                {"entities":{"e1":{"type":"email","attributes":{"key":"email"}}}}""";
        assertThat(SubmissionValidator.cleanPartial(schema, map("email", "ada@"))).containsEntry("email", "ada@");
    }

    @Test
    void skipsRequiredForHiddenOrConditionalFields() {
        // A required field that is hidden / conditionally hidden / computed must NOT 400 when
        // omitted — the server can't evaluate visibility rules and the field may legitimately not
        // be shown (this was the embed-submit 400 in tenants whose form had such a field).
        String schema = """
                {"entities":{
                  "e1":{"type":"text","attributes":{"key":"name","required":true}},
                  "e2":{"type":"email","attributes":{"key":"email","required":true,"hidden":true}},
                  "e3":{"type":"text","attributes":{"key":"ref","required":true,"customConditional":"name == 'x'"}},
                  "e4":{"type":"text","attributes":{"key":"city","required":true,"conditional":{"when":"name","eq":"y"}}},
                  "e5":{"type":"text","attributes":{"key":"zip","required":true,"logic":[{"when":"name == 'z'","action":"hide"}]}}
                }}""";
        // Only the unconditional required field (name) is supplied; the conditional/hidden ones omitted.
        Map<String, Object> out = SubmissionValidator.clean(schema, map("name", "Ada"));
        assertThat(out).containsOnlyKeys("name");
    }

    @Test
    void skipsRequiredForNonPersistentField() {
        // Mirrors the real embed-submit 400: a REQUIRED field with persistent:false. The renderer's
        // collect() deliberately omits non-persistent fields from the payload, so the value never
        // arrives — the backstop must not flag it missing (this is exactly why one tenant's embed
        // submitted fine while another's, whose required field was persistent:false, always 400'd).
        String schema = """
                {"entities":{
                  "e1":{"type":"textField","attributes":{"key":"Text","required":true,"persistent":false}},
                  "e2":{"type":"textarea","attributes":{"key":"textArea"}}
                }}""";
        Map<String, Object> out = SubmissionValidator.clean(schema, map("textArea", "hello"));
        assertThat(out).containsOnlyKeys("textArea"); // no 400, "Text" not required of the payload
    }

    @Test
    void stillEnforcesUnconditionalRequiredAlongsideConditionalFields() {
        String schema = """
                {"entities":{
                  "e1":{"type":"text","attributes":{"key":"name","required":true}},
                  "e2":{"type":"email","attributes":{"key":"email","required":true,"hidden":true}}
                }}""";
        // name (unconditionally required) is still enforced even though a hidden required field exists.
        assertThatThrownBy(() -> SubmissionValidator.clean(schema, map("email", "a@b.com")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("name");
    }

    @Test
    void stripsControlCharactersButKeepsSpacesAndTabs() {
        Map<String, Object> out = SubmissionValidator.clean(
                SCHEMA, map("fullName", "Ada" + NUL + " " + BEL + "Lovelace", "notes", "keep" + TAB + "this"));
        assertThat(out.get("fullName")).isEqualTo("Ada Lovelace"); // NUL + BEL stripped, space kept
        assertThat(out.get("notes")).isEqualTo("keep" + TAB + "this"); // tab preserved
    }

    @Test
    void rejectsTooManyTopLevelKeys() {
        Map<String, Object> big = new LinkedHashMap<>();
        for (int i = 0; i < 600; i++) big.put("k" + i, "v");
        assertThatThrownBy(() -> SubmissionValidator.clean(SCHEMA, big)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cleansNestedCollectionsWithinKnownFields() {
        // a field carrying an array is cleaned recursively (control chars stripped from each element).
        Map<String, Object> out = SubmissionValidator.clean(
                SCHEMA, map("fullName", "Ada", "notes", List.of("a" + BEL, "b")));
        assertThat(out.get("notes")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void partialModeStripsAndBoundsButNeverEnforcesRequired() {
        // Autosave of a half-filled form: the required field is legitimately empty, so cleanPartial
        // must not 400 — otherwise every draft save of an incomplete form fails.
        Map<String, Object> out = SubmissionValidator.cleanPartial(SCHEMA, map("age", 30, "evil", "injected"));
        assertThat(out).containsOnlyKeys("age"); // still strips undeclared keys
    }

    @Test
    void partialModeStillCleansAndBounds() {
        Map<String, Object> out = SubmissionValidator.cleanPartial(SCHEMA, map("notes", "a" + BEL + "b"));
        assertThat(out.get("notes")).isEqualTo("ab");

        Map<String, Object> big = new LinkedHashMap<>();
        for (int i = 0; i < 600; i++) big.put("k" + i, "v");
        assertThatThrownBy(() -> SubmissionValidator.cleanPartial(SCHEMA, big))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cleanIsIdempotent() {
        // The embed door cleans before creating the instance and the choke point cleans again on
        // submit; the second pass must be a no-op rather than mangling the first pass's output.
        Map<String, Object> once = SubmissionValidator.clean(SCHEMA, map("fullName", "Ada" + BEL, "evil", "x"));
        Map<String, Object> twice = SubmissionValidator.clean(SCHEMA, once);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void fallsBackToCleaningAllKeysWhenSchemaHasNoFields() {
        // legacy/unparseable schema → don't drop the whole submission; still clean + bound.
        Map<String, Object> out = SubmissionValidator.clean("{}", map("anything", "a" + NUL + "b"));
        assertThat(out).containsKey("anything");
        assertThat(out.get("anything")).isEqualTo("ab");
    }
}
