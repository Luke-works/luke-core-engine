package com.luke.engine.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.delegate.BpmnError;
import org.springframework.stereotype.Component;

/**
 * Universal response parser for the {@code http-connector} (camunda-connect) used on Service Tasks.
 *
 * <p>The HTTP connector exposes three raw response variables to the connector's output mapping:
 * {@code statusCode} (Integer), {@code response} (the body, as a String) and {@code headers}
 * (a Map). Every task otherwise has to hand-roll its own status check + JSON parse. This bean
 * turns that raw response into one canonical, EL/FEEL-navigable structure so any HTTP service task
 * — regardless of the API it calls — maps its output the same way.
 *
 * <p><b>Wiring</b> (inside a {@code <camunda:connector connectorId="http-connector">} output):
 * <pre>{@code
 * <camunda:outputParameter name="httpResult">
 *   ${httpResponseParser.parseOrThrow(statusCode, response, headers)}
 * </camunda:outputParameter>
 * }</pre>
 * Downstream expressions then read {@code ${httpResult.ok}}, {@code ${httpResult.statusCode}},
 * {@code ${httpResult.body.someField}}, {@code ${httpResult.headers['content-type']}}.
 *
 * <p><b>Result shape</b> (a plain {@link Map}, so JUEL {@code .field} and FEEL both navigate it):
 * <ul>
 *   <li>{@code statusCode} — int</li>
 *   <li>{@code ok} — true for 2xx</li>
 *   <li>{@code contentType} — lower-cased, parameters stripped (e.g. {@code application/json})</li>
 *   <li>{@code headers} — Map with lower-cased keys</li>
 *   <li>{@code body} — parsed JSON (Map/List/scalar) when the body is JSON, else the raw String, else null</li>
 *   <li>{@code raw} — the original body String (null when absent)</li>
 * </ul>
 *
 * <p><b>Failure handling.</b> {@link #parse} never throws — it always returns the structure so a
 * workflow can branch on {@code ok}. {@link #parseOrThrow} raises a {@link BpmnError} on any non-2xx
 * so an error boundary event routes to a fallback path — mirroring how {@code connectorExecutor}
 * signals business failures.
 */
@Component("httpResponseParser")
public class HttpResponseParser {

    /** Default BPMN error code raised by {@link #parseOrThrow} for a non-2xx response. */
    public static final String ERR_HTTP = "http-error";

    private final ObjectMapper mapper;

    public HttpResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Parse the raw connector response into the canonical structure. Never throws. */
    public Map<String, Object> parse(Object statusCode, Object body, Object headers) {
        int status = coerceStatus(statusCode);
        Map<String, String> hdrs = normalizeHeaders(headers);
        String contentType = stripParams(hdrs.get("content-type"));
        String raw = body == null ? null : String.valueOf(body);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statusCode", status);
        result.put("ok", status >= 200 && status < 300);
        result.put("contentType", contentType);
        result.put("headers", hdrs);
        result.put("body", parseBody(raw, contentType));
        result.put("raw", raw);
        return result;
    }

    /** As {@link #parse}, but raise {@link BpmnError} {@value #ERR_HTTP} on any non-2xx status. */
    public Map<String, Object> parseOrThrow(Object statusCode, Object body, Object headers) {
        return parseOrThrow(statusCode, body, headers, ERR_HTTP);
    }

    /** As {@link #parseOrThrow}, letting the caller pick the BPMN error code the boundary listens for. */
    public Map<String, Object> parseOrThrow(Object statusCode, Object body, Object headers, String errorCode) {
        Map<String, Object> result = parse(statusCode, body, headers);
        if (!(Boolean) result.get("ok")) {
            int status = (int) result.get("statusCode");
            String snippet = snippet(result.get("raw"));
            String code = (errorCode == null || errorCode.isBlank()) ? ERR_HTTP : errorCode;
            throw new BpmnError(code, "HTTP " + status + (snippet.isEmpty() ? "" : ": " + snippet));
        }
        return result;
    }

    /** JSON when the content-type says so or the body looks like JSON; otherwise the raw String (or null). */
    private Object parseBody(String raw, String contentType) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        boolean jsonContentType = contentType != null && contentType.contains("json");
        boolean looksJson = trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[';
        if (jsonContentType || looksJson) {
            try {
                // Object.class → Map / List / String / Number / Boolean, whatever the JSON is.
                return mapper.readValue(trimmed, Object.class);
            } catch (Exception e) {
                // Content claimed/looked like JSON but wasn't parseable — keep the raw body rather than fail.
                return raw;
            }
        }
        return raw;
    }

    private static int coerceStatus(Object statusCode) {
        if (statusCode instanceof Number n) {
            return n.intValue();
        }
        if (statusCode != null) {
            try {
                return Integer.parseInt(statusCode.toString().trim());
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return 0;
    }

    private static Map<String, String> normalizeHeaders(Object headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                out.put(e.getKey().toString().toLowerCase(Locale.ROOT),
                        e.getValue() == null ? "" : e.getValue().toString());
            }
        }
        return out;
    }

    /** {@code application/json; charset=utf-8} → {@code application/json}. */
    private static String stripParams(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String snippet(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toString().strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
