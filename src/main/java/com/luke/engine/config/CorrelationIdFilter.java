package com.luke.engine.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every request with a correlation id (observability, #27). Accepts an
 * inbound {@code X-Correlation-Id} (so a trace started at the gateway carries
 * through) or generates one, puts it in the SLF4J {@link MDC} so every log line in
 * the request thread is tagged, and echoes it on the response so a client/operator
 * can quote it. Runs first, before the auth filters, so even rejected requests are
 * traceable.
 *
 * <p>Wire the id into log output via {@code logging.pattern.level} (see application.yml).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = sanitize(req.getHeader(HEADER));
        MDC.put(MDC_KEY, cid);
        res.setHeader(HEADER, cid);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accept a client-supplied id only if it's short and made of safe characters
     * (prevents log-injection / unbounded MDC values); otherwise generate one.
     */
    static String sanitize(String candidate) {
        if (candidate == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9._-]+")) {
            return UUID.randomUUID().toString();
        }
        return trimmed;
    }
}
