package com.luke.engine.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Builds the one error body the engine's {@code /api/**} surface returns (#63).
 *
 * <p>Shape: {@code {error, message, status, correlationId}} — the {@code error}/{@code message}
 * keys match the engine's existing convention (e.g. {@code SignatureExceptionAdvice}) so nothing
 * that reads them breaks; {@code correlationId} ties a client-visible failure to the server log
 * line that holds the real (unsanitized) detail. Used by both {@link GlobalExceptionHandler} and
 * the auth filters, so a controller error and a filter denial are indistinguishable in shape.
 */
public final class ApiError {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiError() {}

    public static Map<String, Object> body(int status, String error, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("message", message);
        m.put("status", status);
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (cid != null && !cid.isBlank()) {
            m.put("correlationId", cid);
        }
        return m;
    }

    /** The correlation id for the in-flight request, or {@code "-"} when unset (for log lines). */
    public static String correlationId() {
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        return (cid == null || cid.isBlank()) ? "-" : cid;
    }

    /**
     * Write the error body straight to the servlet response — for filter/interceptor denials that
     * run before the DispatcherServlet (so {@code @RestControllerAdvice} can't reach them).
     * Jackson-serialized, so a message with quotes can't break the JSON (the previous hand-rolled
     * string concatenation could).
     */
    public static void write(HttpServletResponse res, int status, String error, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(MAPPER.writeValueAsString(body(status, error, message)));
    }
}
