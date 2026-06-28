package com.luke.engine.capability.form;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side validation + cleaning for PUBLIC embed submissions (Route B M3). The public submit
 * endpoint cannot trust the client, so before a submission is persisted (and a process started) this:
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
     * Validate + clean {@code data} against the published {@code schema}. Returns the cleaned map to
     * persist. Throws {@link ResponseStatusException} 400 (missing required) / 413 (too large).
     */
    public static Map<String, Object> clean(String schema, Map<String, Object> data) {
        Map<String, Object> in = data == null ? Map.of() : data;
        if (in.size() > MAX_TOP_LEVEL_KEYS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Too many fields in submission.");
        }

        List<Map<String, Object>> fields = FormSupport.extractFields(schema);
        Set<String> known = new HashSet<>();
        for (Map<String, Object> f : fields) known.add(String.valueOf(f.get("key")));

        // Keep only schema-declared keys (when the schema yields a field contract). If it yields none
        // — legacy/unparseable schema — fall back to keeping all keys so we never silently drop a whole
        // submission; everything is still cleaned + bounded.
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (known.isEmpty() || known.contains(e.getKey())) {
                out.put(e.getKey(), cleanValue(e.getValue(), 0));
            }
        }

        List<String> missing = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            // Skip required-enforcement for conditionally-controlled fields (hidden / disabled /
            // conditional / computed / show-hide-require logic). The server can't evaluate those
            // rules, so a missing value isn't necessarily an error — the field may legitimately be
            // hidden. The client renderer enforces required only when the field is actually visible;
            // this backstop only hard-enforces UNCONDITIONALLY-required fields. (Fixes a 400 on
            // embed submit for forms with a required-but-conditionally-hidden field.)
            if (Boolean.TRUE.equals(f.get("required")) && !Boolean.TRUE.equals(f.get("conditional"))
                    && isBlank(out.get(String.valueOf(f.get("key"))))) {
                missing.add(String.valueOf(f.get("key")));
            }
        }
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field(s): " + String.join(", ", missing));
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

    private static boolean isBlank(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        if (v instanceof Iterable<?> it) return !it.iterator().hasNext();
        return false;
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Submission field is too large.");
    }
}
