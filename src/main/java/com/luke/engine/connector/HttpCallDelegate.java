package com.luke.engine.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.finos.fluxnova.bpm.engine.delegate.BpmnError;
import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.finos.fluxnova.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Global HTTP call handler for Service Tasks ({@code ${httpCall}}) — the single compiled backend
 * behind the "HTTP Call" element template. Everything that repeats across web-service calls lives
 * here once, so a workflow author never rewrites it: execute → log → parse (map/list) → handle
 * every failure mode. The author configures a task purely at design time (template fields); no
 * inline scripts are ever written or deployed.
 *
 * <p><b>Inputs</b> are read from the task's {@code camunda:inputParameter}s (so expressions like
 * {@code https://api/${id}} evaluate). The template sets them:
 * <ul>
 *   <li>{@code url} (required), {@code method} (default GET), {@code headers} (Map or JSON string),
 *       {@code body} (String/object), {@code timeoutMs} (default 30000)</li>
 *   <li>{@code resultVariable} (default {@code httpResult}) — the OUTPUT process variable that
 *       receives the parsed {@link HttpResponseParser} structure {statusCode, ok, contentType,
 *       headers, body, raw}. Input parameters are task-local; this one persists.</li>
 *   <li>Per-failure-mode handling, each {@code error} | {@code retry} | {@code ignore}:
 *       {@code onClientError} (4xx, default error), {@code onServerError} (5xx, default retry),
 *       {@code onAuthError} (401/403, default error), {@code onRateLimit} (429, default retry),
 *       {@code onTimeout} (IO/timeout, default retry)</li>
 * </ul>
 *
 * <p><b>Failure semantics.</b> {@code error} sets the result variable then throws a {@link BpmnError}
 * (so a boundary event routes to a fallback path); {@code retry} rethrows so the async job executor
 * retries (task must be asyncBefore + have a retry cycle); {@code ignore} logs and continues with
 * {@code ok:false}. Mirrors how {@code connectorExecutor} classifies outcomes.
 */
@Component("httpCall")
public class HttpCallDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(HttpCallDelegate.class);

    // BPMN error codes an error-boundary can listen for.
    public static final String ERR_CLIENT = "http-client-error";   // 4xx
    public static final String ERR_SERVER = "http-server-error";   // 5xx
    public static final String ERR_AUTH = "http-auth-error";       // 401/403
    public static final String ERR_RATE_LIMIT = "http-rate-limit"; // 429
    public static final String ERR_TIMEOUT = "http-timeout";       // IO / timeout / interrupted
    public static final String ERR_CONFIG = "http-config-error";

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final HttpResponseParser parser;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpCallDelegate(HttpResponseParser parser, ObjectMapper mapper) {
        this.parser = parser;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void execute(DelegateExecution execution) {
        String target = required(str(execution, "url"), "url");
        String verb = orDefault(str(execution, "method"), "GET").toUpperCase(Locale.ROOT);
        long timeout = toLong(execution.getVariable("timeoutMs"), DEFAULT_TIMEOUT_MS);
        String resultVar = orDefault(str(execution, "resultVariable"), "httpResult");

        HttpRequest request = buildRequest(execution, target, verb, timeout);

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            log.info("HTTP {} {}", verb, target);
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // No HTTP response at all — connection refused, DNS, timeout, interrupted.
            long ms = elapsedMs(started);
            log.warn("HTTP {} {} failed after {} ms: {}", verb, target, ms, e.toString());
            handleFailure(mode(execution, "onTimeout", "retry"),
                    ERR_TIMEOUT, "HTTP call to " + target + " failed: " + e);
            return;
        }

        int status = response.statusCode();
        long ms = elapsedMs(started);
        Map<String, Object> result = parser.parse(status, response.body(), flattenHeaders(response));
        execution.setVariable(resultVar, result);
        applyAssertions(execution, result);

        if (status >= 200 && status < 300) {
            log.info("HTTP {} {} -> {} ({} ms)", verb, target, status, ms);
            return;
        }

        // Non-2xx: pick the handling the author selected for this class of failure.
        String category;
        String errorCode;
        String modeStr;
        if (status == 401 || status == 403) {
            category = "auth"; errorCode = ERR_AUTH; modeStr = mode(execution, "onAuthError", "error");
        } else if (status == 429) {
            category = "rate-limit"; errorCode = ERR_RATE_LIMIT; modeStr = mode(execution, "onRateLimit", "retry");
        } else if (status >= 500) {
            category = "server"; errorCode = ERR_SERVER; modeStr = mode(execution, "onServerError", "retry");
        } else {
            category = "client"; errorCode = ERR_CLIENT; modeStr = mode(execution, "onClientError", "error");
        }
        log.warn("HTTP {} {} -> {} ({} ms) [{} error, handling={}]", verb, target, status, ms, category, modeStr);
        handleFailure(modeStr, errorCode, "HTTP " + status + " from " + target + snippet(response.body()));
    }

    /** Apply the author-selected mode for a failure: error (BpmnError), retry (rethrow), or ignore. */
    private void handleFailure(String mode, String errorCode, String message) {
        switch (mode == null ? "error" : mode.toLowerCase(Locale.ROOT)) {
            case "ignore" -> {
                // result variable already set (or absent for no-response); continue the flow.
            }
            case "retry" ->
                // Non-BpmnError → the async job executor retries per the task's retry cycle.
                throw new HttpCallRetryable(message);
            default -> // "error"
                throw new BpmnError(errorCode, message);
        }
    }

    private HttpRequest buildRequest(DelegateExecution execution, String target, String verb, long timeout) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofMillis(timeout));

        String payload = str(execution, "body");
        boolean hasBody = payload != null && !payload.isBlank()
                && (verb.equals("POST") || verb.equals("PUT") || verb.equals("PATCH") || verb.equals("DELETE"));
        rb.method(verb, hasBody ? HttpRequest.BodyPublishers.ofString(payload) : HttpRequest.BodyPublishers.noBody());

        applyHeaders(rb, execution.getVariable("headers"));
        return rb.build();
    }

    @SuppressWarnings("unchecked")
    private void applyHeaders(HttpRequest.Builder rb, Object rawHeaders) {
        if (rawHeaders instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    rb.header(e.getKey().toString(), e.getValue().toString());
                }
            }
        } else if (rawHeaders instanceof String s && !s.isBlank()) {
            // Allow a JSON object string as headers too.
            try {
                Map<String, Object> m = mapper.readValue(s, Map.class);
                m.forEach((k, v) -> { if (v != null) rb.header(k, v.toString()); });
            } catch (Exception ignore) {
                log.warn("Ignoring unparseable headers string");
            }
        }
    }

    private static Map<String, Object> flattenHeaders(HttpResponse<String> response) {
        Map<String, Object> out = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> out.put(k, v.size() == 1 ? v.get(0) : v));
        return out;
    }

    // --- assertions: extract a path from the response, compare to a static, set a variable --------

    /**
     * Evaluate the optional {@code assertions} rules against the parsed result. Each rule:
     * {@code { "path": "body.status", "op": "eq", "value": "ok", "setVar": "approved", "setValue": true }}.
     * {@code path} navigates the result map/list (dot segments; numeric segment = list index). When the
     * comparison holds, {@code setVar} is set to {@code setValue} (default {@code true}). {@code op}
     * defaults to {@code eq}; also: ne, gt, lt, ge, le, contains, exists, absent.
     */
    private void applyAssertions(DelegateExecution execution, Map<String, Object> result) {
        List<Map<String, Object>> rules = parseAssertions(execution.getVariable("assertions"));
        for (Map<String, Object> rule : rules) {
            String path = asString(rule.get("path"));
            String setVar = asString(rule.get("setVar"));
            if (path == null || setVar == null) {
                continue;
            }
            Object extracted = navigate(result, path);
            String op = asString(rule.getOrDefault("op", "eq"));
            Object operand = rule.containsKey("value") ? rule.get("value") : rule.get("equals");
            if (compare(extracted, op, operand)) {
                Object setValue = rule.containsKey("setValue") ? rule.get("setValue") : Boolean.TRUE;
                execution.setVariable(setVar, setValue);
                log.debug("assertion matched: {} {} {} -> {} = {}", path, op, operand, setVar, setValue);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseAssertions(Object raw) {
        try {
            if (raw instanceof String s && !s.isBlank()) {
                return mapper.readValue(s, new TypeReference<List<Map<String, Object>>>() { });
            }
            if (raw instanceof List<?> l) {
                return (List<Map<String, Object>>) l;
            }
        } catch (Exception e) {
            log.warn("Ignoring unparseable 'assertions': {}", e.getMessage());
        }
        return List.of();
    }

    /** Navigate {@code root} by a dot path; numeric segments index into lists. Null if unresolved. */
    private static Object navigate(Object root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(seg);
            } else if (cur instanceof List<?> list) {
                try {
                    int i = Integer.parseInt(seg);
                    cur = (i >= 0 && i < list.size()) ? list.get(i) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return cur;
    }

    private static boolean compare(Object extracted, String op, Object operand) {
        switch (op == null ? "eq" : op.toLowerCase(Locale.ROOT)) {
            case "ne", "notequals" -> { return !valuesEqual(extracted, operand); }
            case "gt" -> { Integer c = numCompare(extracted, operand); return c != null && c > 0; }
            case "lt" -> { Integer c = numCompare(extracted, operand); return c != null && c < 0; }
            case "ge" -> { Integer c = numCompare(extracted, operand); return c != null && c >= 0; }
            case "le" -> { Integer c = numCompare(extracted, operand); return c != null && c <= 0; }
            case "contains" -> { return contains(extracted, operand); }
            case "exists", "present" -> { return extracted != null; }
            case "absent", "missing" -> { return extracted == null; }
            default -> { return valuesEqual(extracted, operand); } // eq / equals
        }
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        Double da = toDouble(a);
        Double db = toDouble(b);
        if (da != null && db != null) {
            return da.doubleValue() == db.doubleValue();
        }
        return a.toString().equals(b.toString());
    }

    private static Integer numCompare(Object a, Object b) {
        Double da = toDouble(a);
        Double db = toDouble(b);
        return (da == null || db == null) ? null : Double.compare(da, db);
    }

    private static boolean contains(Object extracted, Object operand) {
        if (extracted instanceof String s) {
            return operand != null && s.contains(operand.toString());
        }
        if (extracted instanceof Collection<?> c) {
            return c.stream().anyMatch(x -> valuesEqual(x, operand));
        }
        if (extracted instanceof Map<?, ?> m) {
            return operand != null && m.containsKey(operand.toString());
        }
        return false;
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException ignore) {
                // not numeric
            }
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    // --- variable / coercion helpers --------------------------------------------------------------

    private static String str(DelegateExecution ex, String name) {
        Object v = ex.getVariable(name);
        return v == null ? null : v.toString();
    }

    private static String mode(DelegateExecution ex, String name, String dflt) {
        String s = str(ex, name);
        return (s == null || s.isBlank()) ? dflt : s.trim();
    }

    private static String orDefault(String s, String dflt) {
        return (s == null || s.isBlank()) ? dflt : s;
    }

    private static String required(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new BpmnError(ERR_CONFIG, "HTTP call is missing required field '" + name + "'");
        }
        return s;
    }

    private static long toLong(Object v, long dflt) {
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try { return Long.parseLong(v.toString().trim()); } catch (NumberFormatException ignore) { }
        }
        return dflt;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) return "";
        String s = body.strip();
        return ": " + (s.length() > 160 ? s.substring(0, 160) + "…" : s);
    }

    /** Marker runtime exception for the {@code retry} mode — a non-BpmnError so the job executor retries. */
    static final class HttpCallRetryable extends RuntimeException {
        HttpCallRetryable(String message) { super(message); }
    }
}
