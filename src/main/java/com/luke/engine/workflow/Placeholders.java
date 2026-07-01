package com.luke.engine.workflow;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{ dotted.path }}} placeholders in an action input string against a
 * snapshot of the process variables — so a workflow can write inputs like
 * {@code "to": "{{ submission.email }}"}. Tolerant: unknown paths (or null variables)
 * resolve to an empty string, never an exception.
 */
public final class Placeholders {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    private Placeholders() {}

    /** Replace every {@code {{path}}} in {@code template} with the looked-up variable value. */
    public static String resolve(String template, Map<String, Object> variables) {
        if (template == null || template.indexOf("{{") < 0) return template;
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = lookup(variables, m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Walk a dotted path through nested maps; null on any miss. */
    private static Object lookup(Map<String, Object> variables, String path) {
        Object current = variables;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
            if (current == null) return null;
        }
        return current;
    }
}
