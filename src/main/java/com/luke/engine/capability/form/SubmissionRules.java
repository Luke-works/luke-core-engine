package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.luke.engine.capability.form.FormSupport.FieldRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The declarative validation rules, server side — a deliberate Java mirror of
 * {@code @lukeflow/form-core}'s {@code validation/builtins.ts}.
 *
 * <h2>Why this exists twice</h2>
 * Every rule here also exists in TypeScript and runs in the filler's browser. The browser copy is
 * the one users experience; this copy is the one a TAMPERED payload cannot skip. A submission that
 * never touches the renderer — a hand-rolled POST to the public endpoint — would otherwise land
 * arbitrary values in fields the author constrained.
 *
 * <h2>How the two are kept honest</h2>
 * {@code luke-forms/fixtures/validation-parity.json} is a shared case table executed against BOTH
 * implementations ({@code parity.test.ts} there, {@code SubmissionValidatorParityTest} here). Any
 * behavioural divergence fails a build. The two repos share no CI, so the fixture also carries a
 * {@code revision} that the Java test pins — bumping the rules in one language without the other
 * fails loudly at review rather than silently in production.
 *
 * <h2>Deliberate scope limits</h2>
 * <ul>
 *   <li><b>Evaluation order matters</b> — rules run head-to-tail and the FIRST failure wins, so a
 *       field reports one error, matching the renderer.</li>
 *   <li><b>Empty values skip every rule but {@code required}</b> — a bound constrains a value that
 *       exists; "absent" is {@code required}'s question alone.</li>
 *   <li><b>Fail-open on malformed authoring</b> — a bad regex or a non-numeric bound is treated as
 *       "no constraint", never as a rejection. A broken form must not become an unsubmittable one.</li>
 *   <li><b>No expressions, no author JS, no async/minion checks</b> — those need the evaluation
 *       scope (and a JS runtime) and stay client-side by design. Documented in the fixture.</li>
 * </ul>
 */
final class SubmissionRules {

    private SubmissionRules() {}

    /** Mirrors form-core's EMAIL_RE. */
    private static final Pattern EMAIL_RE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    /** Mirrors form-core's URL_RE. */
    private static final Pattern URL_RE = Pattern.compile("^https?://\\S+$");
    /** Whitespace runs, for word counting (mirrors the JS `\s+` split). */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /**
     * What JS `Number(x)` accepts for a realistic form value. Java's {@code Double.parseDouble}
     * additionally accepts "12d"/"12f"/hex, which would apply a numeric bound where the browser
     * applied none — so gate on this first and treat anything else as unconstrained.
     */
    private static final Pattern NUMERIC = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    /** The parts a required {@code addressBlock} must all carry — everything except the suite/apt. */
    private static final String[] ADDRESS_REQUIRED_PARTS = {"streetAddress", "city", "region", "postalCode", "country"};

    /**
     * Validate one value against one field's declarative rules. Returns the failing rule's code, or
     * {@code null} when the value passes.
     *
     * <p>{@code required} is NOT checked here — it depends on whether the server may hard-enforce it
     * for this field (conditionally-controlled fields are exempt), which is
     * {@link SubmissionValidator}'s call. Everything here is value-shape only.
     */
    static String firstFailure(FieldRule field, Object value) {
        JsonNode a = field.attributes();
        String type = field.type();

        // Every rule below constrains a value that EXISTS. Absent → nothing to check.
        if (isEmpty(value)) return countFailure(a, value); // count rules still see "absent" as zero

        String s = value instanceof String str ? str : null;

        // ── order mirrors form-core's BUILTIN_RULES exactly ──────────────────────────────
        if (s != null) {
            Double minLength = num(a, "minLength");
            if (minLength != null && s.length() < minLength) return "minLength";
            Double maxLength = num(a, "maxLength");
            if (maxLength != null && s.length() > maxLength) return "maxLength";

            Double minWords = num(a, "minWords");
            if (minWords != null && wordCount(s) < minWords) return "minWords";
            Double maxWords = num(a, "maxWords");
            if (maxWords != null && wordCount(s) > maxWords) return "maxWords";

            String pattern = str(a, "pattern");
            if (!pattern.isEmpty()) {
                Pattern compiled = compile(pattern);
                // A malformed regex is inert, matching the renderer's swallow. JS `re.test()` is a
                // SEARCH, not a full match — so this must be find(), not matches().
                if (compiled != null && !compiled.matcher(s).find()) return "pattern";
            }

            if ("email".equals(type) && !EMAIL_RE.matcher(s).find()) return "email";
            if ("url".equals(type) && !URL_RE.matcher(s).find()) return "url";
        }

        Double numeric = numericValue(value);
        if (numeric != null) {
            Double min = num(a, "min");
            if (min != null && numeric < min) return "min";
            Double max = num(a, "max");
            if (max != null && numeric > max) return "max";
        }

        if (s != null) {
            String minDate = str(a, "minDate");
            if (!minDate.isEmpty() && s.compareTo(minDate) < 0) return "minDate";
            String maxDate = str(a, "maxDate");
            if (!maxDate.isEmpty() && s.compareTo(maxDate) > 0) return "maxDate";
            String minTime = str(a, "minTime");
            if (!minTime.isEmpty() && s.compareTo(minTime) < 0) return "minTime";
            String maxTime = str(a, "maxTime");
            if (!maxTime.isEmpty() && s.compareTo(maxTime) > 0) return "maxTime";
        }

        String countFailure = countFailure(a, value);
        if (countFailure != null) return countFailure;

        Double maxSize = num(a, "maxSize");
        if (maxSize != null) {
            double limitBytes = maxSize * 1024 * 1024;
            for (Object file : asList(value)) {
                if (file instanceof Map<?, ?> m && m.get("size") instanceof Number n && n.doubleValue() > limitBytes) {
                    return "maxFileSize";
                }
            }
        }

        // ── server-only, past the parity set ─────────────────────────────────────────────
        // The browser constrains choice by only RENDERING the declared options; there is no
        // form-core validator to be in parity with. A tampered payload has no such constraint,
        // which is precisely why the server checks it.
        return optionFailure(type, a, value);
    }

    /** The array-count rules, which (like form-core) treat a non-array value as a count of zero. */
    private static String countFailure(JsonNode a, Object value) {
        int n = value instanceof Collection<?> c ? c.size() : 0;
        String[][] bounds = {
            {"minSelected", "min"}, {"maxSelected", "max"},
            {"minTags", "min"}, {"maxTags", "max"},
            {"minRows", "min"}, {"maxRows", "max"},
            {"minFiles", "min"}, {"maxFiles", "max"},
        };
        for (String[] pair : bounds) {
            Double bound = num(a, pair[0]);
            if (bound == null) continue;
            boolean failed = "min".equals(pair[1]) ? n < bound : n > bound;
            if (failed) return pair[0];
        }
        return null;
    }

    /**
     * The field types whose value must be one of the declared options. Deliberately an ALLOW-LIST of
     * the types the builder writes an {@code options} attribute for, rather than "any field that
     * happens to carry options": a survey matrix keeps its choices under {@code rows}/{@code columns}
     * and stores an object, so treating a stray options-like attribute as a membership constraint
     * risks rejecting a perfectly valid submission. Under-checking is the safe direction here.
     */
    private static final Set<String> OPTION_BEARING_TYPES =
            Set.of("select", "searchSelect", "radio", "selectBoxes", "ranking");

    /**
     * SERVER-ONLY: the submitted value(s) must be among the options the schema declares. Skipped
     * when the field loads its options from a minion — those aren't in the schema, so there is no
     * list to check against.
     */
    private static String optionFailure(String type, JsonNode a, Object value) {
        if (!OPTION_BEARING_TYPES.contains(type)) return null;

        JsonNode dataSource = a.path("dataSource");
        if (dataSource.isObject() && dataSource.size() > 0) return null;

        Set<String> allowed = declaredOptionValues(a);
        if (allowed.isEmpty()) return null;

        if (value instanceof Collection<?> c) {
            for (Object item : c) {
                if (!allowed.contains(String.valueOf(item))) return "option";
            }
            return null;
        }
        return allowed.contains(String.valueOf(value)) ? null : "option";
    }

    /** The option VALUES a field declares, across the shapes the builder writes. */
    private static Set<String> declaredOptionValues(JsonNode a) {
        JsonNode raw = a.path("options");
        if (!raw.isArray()) raw = a.path("values");
        if (!raw.isArray()) raw = a.path("data").path("values");
        Set<String> out = new LinkedHashSet<>();
        if (!raw.isArray()) return out;
        for (JsonNode option : raw) {
            if (option.isTextual()) out.add(option.asText());
            else if (option.isObject()) out.add(option.path("value").asText(""));
            else if (option.isNumber() || option.isBoolean()) out.add(option.asText());
        }
        return out;
    }

    /**
     * Whether a REQUIRED field is satisfied — mirrors form-core's {@code requiredRule}, which is
     * subtler than "is it blank": an unchecked box and an all-empty matrix both count as missing,
     * a required address needs every part but the suite, and whitespace counts as PRESENT.
     */
    static boolean satisfiesRequired(FieldRule field, Object value) {
        if ("addressBlock".equals(field.type())) {
            Map<?, ?> parts = value instanceof Map<?, ?> m ? m : Map.of();
            for (String part : ADDRESS_REQUIRED_PARTS) {
                if (isEmpty(parts.get(part))) return false;
            }
            return true;
        }
        // A boolean field is "provided" only when actively true — an unchecked consent box is missing,
        // not merely false. (Plain isEmpty() would call `false` a present value.)
        if (value instanceof Boolean b) return b;
        return !isEmpty(value);
    }

    /**
     * Mirrors form-core's {@code isEmptyValue}. Note whitespace is NOT empty: the renderer accepts
     * "   " in a required field, so the server must too, or it 400s a submission the user was told
     * was fine. An object is empty when it has no entries, or every entry is itself empty (one level
     * — matching the JS, which does not recurse).
     */
    static boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isEmpty();
        if (v instanceof Collection<?> c) return c.isEmpty();
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) return true;
            for (Object x : m.values()) {
                boolean entryEmpty = x == null || "".equals(x) || (x instanceof Collection<?> c && c.isEmpty());
                if (!entryEmpty) return false;
            }
            return true;
        }
        return false;
    }

    // ── attribute + value coercion (mirrors asNum / asStr / Number()) ────────────────────

    private static Double num(JsonNode a, String name) {
        JsonNode n = a.path(name);
        return n.isNumber() ? n.doubleValue() : null;
    }

    private static String str(JsonNode a, String name) {
        JsonNode n = a.path(name);
        return n.isTextual() ? n.asText() : "";
    }

    /** The numeric reading of a value, or null when it carries no numeric meaning. */
    private static Double numericValue(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (!NUMERIC.matcher(trimmed).matches()) return null;
            try {
                double d = Double.parseDouble(trimmed);
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null; // booleans, maps, lists carry no numeric constraint
    }

    private static int wordCount(String s) {
        String trimmed = s.trim();
        return trimmed.isEmpty() ? 0 : WHITESPACE.split(trimmed).length;
    }

    private static Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return null; // malformed author regex → inert, never a rejection
        }
    }

    private static List<?> asList(Object value) {
        return value instanceof Collection<?> c ? new ArrayList<>(c) : List.of();
    }
}
