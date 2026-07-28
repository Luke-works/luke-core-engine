package com.luke.engine.capability.form;

import com.luke.engine.capability.form.FormSupport.FieldRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side validation + cleaning for EVERY submission, whatever door it arrived through. No
 * submit path may trust the client, so before a submission is persisted (and a process started) this:
 *
 * <ul>
 *   <li><b>Strips unknown fields</b> — only keys declared by the published schema survive, so a
 *       tampered payload can't inject arbitrary data into the process.</li>
 *   <li><b>Enforces required fields</b> — a missing/blank required field is a 400 (the renderer
 *       already blocks this; this is the server-side backstop).</li>
 *   <li><b>Bounds size + depth</b> — caps field count, string length, collection size and nesting,
 *       so a hostile payload can't blow up storage / downstream processing (413).</li>
 *   <li><b>Strips control characters</b> from strings (defense-in-depth; output-encoding for XSS is
 *       the renderer's job — we deliberately do NOT HTML-escape here, which would corrupt values
 *       like "a &lt; b").</li>
 * </ul>
 *
 * <p>Two modes, because a draft is not a submission:
 * <ul>
 *   <li>{@link #clean} — SUBMIT. Everything above, including the required backstop.</li>
 *   <li>{@link #cleanPartial} — AUTOSAVE/DRAFT. Strips + bounds only; a half-filled draft legitimately
 *       has empty required fields, so enforcing them would make autosave impossible.</li>
 * </ul>
 *
 * <p>Called from {@link FormSubmissionService#submit} — the single choke point every door funnels
 * through — rather than per-controller, so a future submit path cannot forget it.
 *
 * <p>This intentionally does NOT replicate form-core's expression/conditional rules — those stay
 * client-side. It is the structural + abuse backstop, kept dependency-free.
 */
public final class SubmissionValidator {

    private SubmissionValidator() {}

    static final int MAX_TOP_LEVEL_KEYS = 500;
    static final int MAX_STRING_LEN = 100_000; // generous: fits a signature/data-URL; truncates beyond
    static final int MAX_COLLECTION = 1_000;
    static final int MAX_DEPTH = 8;

    /**
     * SUBMIT mode — validate + clean {@code data} against the published {@code schema}. Returns the
     * cleaned map to persist. Throws {@link ResponseStatusException} 400 (missing required) / 413
     * (too large).
     *
     * <p>Callers holding an instance with previously-saved data must pass the MERGED map (stored +
     * incoming), not the incoming delta — required-enforcement asks "is this submission complete",
     * which a delta can't answer.
     */
    public static Map<String, Object> clean(String schema, Map<String, Object> data) {
        return clean(schema, data, true);
    }

    /**
     * DRAFT mode — strip unknown keys + bound size, but do NOT enforce required. For autosave of a
     * partially-filled form, where empty required fields are the normal state rather than an error.
     */
    public static Map<String, Object> cleanPartial(String schema, Map<String, Object> data) {
        return clean(schema, data, false);
    }

    private static Map<String, Object> clean(String schema, Map<String, Object> data, boolean enforceRequired) {
        Map<String, Object> in = data == null ? Map.of() : data;
        if (in.size() > MAX_TOP_LEVEL_KEYS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Too many fields in submission.");
        }

        List<FieldRule> fields = FormSupport.extractFieldRules(schema);
        Set<String> known = new HashSet<>();
        for (FieldRule f : fields) known.add(f.key());

        // Keep only schema-declared keys (when the schema yields a field contract). If it yields none
        // — legacy/unparseable schema — fall back to keeping all keys so we never silently drop a whole
        // submission; everything is still cleaned + bounded.
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (known.isEmpty() || known.contains(e.getKey())) {
                out.put(e.getKey(), cleanValue(e.getValue(), 0));
            }
        }

        if (!enforceRequired) return out;

        List<String> missing = new ArrayList<>();
        for (FieldRule f : fields) {
            // Skip required-enforcement for conditionally-controlled fields (hidden / disabled /
            // conditional / computed / show-hide-require logic). The server can't evaluate those
            // rules, so a missing value isn't necessarily an error — the field may legitimately be
            // hidden. The client renderer enforces required only when the field is actually visible;
            // this backstop only hard-enforces UNCONDITIONALLY-required fields. (Fixes a 400 on
            // embed submit for forms with a required-but-conditionally-hidden field.)
            if (f.required() && !f.conditional() && !SubmissionRules.satisfiesRequired(f, out.get(f.key()))) {
                missing.add(f.key());
            }
        }
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field(s): " + String.join(", ", missing));
        }

        // Value-shape rules (length, pattern, email/url, numeric + date bounds, counts, option
        // membership).
        //
        // A conditionally-controlled field is checked ONLY when it actually carries a value. Most
        // rules are inert on an absent value, but the COUNT rules are not — they read a missing
        // value as "zero items", so a hidden field carrying `minFiles: 1` would reject a submission
        // the renderer considered complete (it never showed the field, and collect() omits it). That
        // is the same false-400 that conditionally-hidden REQUIRED fields once caused on embed
        // submit. A value being present is the signal the field was live: when one did arrive, it is
        // held to everything the author declared, so nothing can be smuggled into a hidden field.
        List<String> invalid = new ArrayList<>();
        for (FieldRule f : fields) {
            if (f.conditional() && SubmissionRules.isEmpty(out.get(f.key()))) continue;
            String code = SubmissionRules.firstFailure(f, out.get(f.key()));
            if (code != null) invalid.add(f.key() + " (" + code + ")");
        }
        if (!invalid.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid field(s): " + String.join(", ", invalid));
        }
        return out;
    }

    private static Object cleanValue(Object v, int depth) {
        if (v == null) return null;
        if (depth > MAX_DEPTH) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Submission nested too deeply.");
        }
        if (v instanceof String s) return cleanString(s);
        if (v instanceof Map<?, ?> m) {
            if (m.size() > MAX_COLLECTION) throw tooLarge();
            Map<String, Object> out = new LinkedHashMap<>();
            // Clean KEYS too, not just values — for a Json-typed field the attacker controls the nested
            // object's keys, which would otherwise smuggle control chars / unbounded length past the caps.
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(cleanString(String.valueOf(e.getKey())), cleanValue(e.getValue(), depth + 1));
            return out;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            int n = 0;
            for (Object e : it) {
                if (++n > MAX_COLLECTION) throw tooLarge();
                out.add(cleanValue(e, depth + 1));
            }
            return out;
        }
        return v; // Number, Boolean — stored as-is
    }

    /** Drop C0/C1 control chars (keep tab/newline/cr) and DEL; truncate to the per-string cap. */
    private static String cleanString(String s) {
        int cap = Math.min(s.length(), MAX_STRING_LEN);
        StringBuilder b = new StringBuilder(cap);
        for (int i = 0; i < s.length() && b.length() < MAX_STRING_LEN; i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0x7F)) b.append(c);
        }
        return b.toString();
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Submission field is too large.");
    }
}
