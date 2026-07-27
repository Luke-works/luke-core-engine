package com.luke.engine.capability.access;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Guards {@code /actuator/prometheus} (OBS-2) so the metrics scrape is NEVER public. The rest of
 * core-engine is {@code permitAll()}, so without this the Prometheus endpoint — JVM, HTTP-route and
 * HikariCP pool internals — would be world-readable.
 *
 * <p>Behaviour keyed on {@code MANAGEMENT_METRICS_TOKEN}:
 * <ul>
 *   <li>UNSET → the endpoint responds <b>404</b> (as if absent). Safe default: dev/qa boot fine and
 *       nothing is exposed until a scrape token is deliberately configured.</li>
 *   <li>SET → requires {@code Authorization: Bearer <token>} (constant-time compare), else 401. The
 *       Grafana Alloy collector sends the token; anything else is refused.</li>
 * </ul>
 * Health/info/metrics endpoints are untouched — Render's health probe keeps working on the main port.
 */
@Configuration
public class MetricsScrapeAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsScrapeAuthFilter.class);

    @Bean
    public FilterRegistrationBean<Filter> metricsScrapeAuthFilterRegistration(
            @Value("${MANAGEMENT_METRICS_TOKEN:}") String token) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl(token));
        reg.addUrlPatterns("/actuator/prometheus");
        reg.setName("metricsScrapeAuthFilter");
        reg.setOrder(0);
        return reg;
    }

    static class Impl implements Filter { // package-private for direct unit testing
        private final String expected; // the scrape token, or null when unconfigured

        Impl(String token) {
            if (token != null && !token.isBlank()) {
                this.expected = token;
                log.info("MetricsScrapeAuthFilter: ENABLED — /actuator/prometheus requires a Bearer scrape token.");
            } else {
                this.expected = null;
                log.info("MetricsScrapeAuthFilter: disabled (MANAGEMENT_METRICS_TOKEN unset) — "
                        + "/actuator/prometheus is 404 (never public). Set the token to enable scraping.");
            }
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;

            if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
            // Unconfigured ⇒ act as if the endpoint doesn't exist (never public).
            if (expected == null) {
                com.luke.engine.web.ApiError.write(res, HttpServletResponse.SC_NOT_FOUND, "Not Found", "Not found");
                return;
            }
            String presented = req.getHeader("Authorization");
            if (presented == null || !constantTimeEquals(presented, "Bearer " + expected)) {
                com.luke.engine.web.ApiError.write(res, HttpServletResponse.SC_UNAUTHORIZED,
                        "Unauthorized", "A valid metrics scrape token is required");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean constantTimeEquals(String a, String b) {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }
    }
}
