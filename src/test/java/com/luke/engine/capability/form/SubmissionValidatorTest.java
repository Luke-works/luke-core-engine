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
        // present-but-blank required also rejected
        assertThatThrownBy(() -> SubmissionValidator.clean(SCHEMA, map("fullName", "   ")))
                .isInstanceOf(ResponseStatusException.class);
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
    void fallsBackToCleaningAllKeysWhenSchemaHasNoFields() {
        // legacy/unparseable schema → don't drop the whole submission; still clean + bound.
        Map<String, Object> out = SubmissionValidator.clean("{}", map("anything", "a" + NUL + "b"));
        assertThat(out).containsKey("anything");
        assertThat(out.get("anything")).isEqualTo("ab");
    }
}
