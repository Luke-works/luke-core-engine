package com.luke.engine.config;

import com.luke.engine.web.ApiError;
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
import java.util.Base64;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Default-deny authentication baseline for {@code /api/**} (#20).
 *
 * <p>Historically every {@code /api} controller was individually responsible for authenticating its
 * own caller (Spring Security {@code permitAll}s everything, and the hand-registered filters cover
 * only specific path prefixes). A controller added without auth was therefore reachable with zero
 * credentials — this has failed several times. This filter is the last-resort gate: any {@code /api}
 * request that is neither explicitly allow-listed nor carrying a recognized credential is denied,
 * so a NEW controller is authenticated-by-default.
 *
 * <p><b>DEFAULT-LENIENT.</b> Enforcement is opt-in: it activates only under the dedicated
 * {@code prod} profile ({@link StrictProfile}) or {@code luke.auth.api-default-deny=true}. dev/qa
 * (which run {@code postgres} only, with the gateway JWKS unset) pass through UNCHANGED — the filter
 * short-circuits before doing any work — so this can never crash them. The concrete gaps this closes
 * (previously-unguarded routes) are fixed independently by wiring them into the specific filters;
 * this baseline is the prod defense-in-depth that stops the NEXT gap.
 *
 * <p>Recognized credentials (any one suffices — this gates authentication, not authorization; the
 * specific filters/controllers still do their own authZ): a valid gateway act-as Bearer
 * ({@link GatewayJwtAuthenticator}), the operator HTTP-Basic credential, or an engine Basic
 * username/password. Allow-listed without a credential: {@code OPTIONS} preflight, {@code /api/public/**},
 * {@code /api/internal/**} (its own fail-closed {@code InternalAuthFilter} runs first), and the
 * intentionally-public global-catalog reads {@code GET /api/capabilities[/{code}]}.
 */
@Configuration
public class ApiDefaultDenyFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiDefaultDenyFilter.class);

    @Bean
    public FilterRegistrationBean<Filter> apiDefaultDenyFilterRegistration(
            IdentityService identityService,
            GatewayJwtAuthenticator gatewayAuth,
            @Value("${luke.auth.operator.user:}") String operatorUser,
            @Value("${luke.auth.operator.password:}") String operatorPassword,
            @Value("${luke.auth.api-default-deny:false}") boolean flag,
            Environment environment) {
        boolean enforce = flag || StrictProfile.isActive(environment);
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl(identityService, gatewayAuth, operatorUser, operatorPassword, enforce));
        reg.addUrlPatterns("/api/*");
        reg.setName("apiDefaultDenyFilter");
        // Last — after InternalAuthFilter/OperatorAuthFilter (0) and GatewayAuthFilter/ApiAuthFilter (1),
        // so their specific 401s win; this only catches paths those filters don't cover.
        reg.setOrder(2);
        if (enforce) {
            log.info("ApiDefaultDenyFilter: ENFORCING — every /api/** request must authenticate or be "
                    + "allow-listed (/api/public/**, /api/internal/**, GET /api/capabilities).");
        } else {
            log.info("ApiDefaultDenyFilter: pass-through (local-dev posture). The 'prod' profile or "
                    + "luke.auth.api-default-deny=true turns on the default-deny baseline.");
        }
        return reg;
    }

    static class Impl implements Filter {

        /** Request attribute holding the resolved caller id, for handlers that want it. */
        public static final String PRINCIPAL_ATTRIBUTE = "luke.api.principal";

        private final IdentityService identityService;
        private final GatewayJwtAuthenticator gatewayAuth;
        private final String operatorBasic; // "Basic base64(user:pass)" or null when unconfigured
        private final boolean enforce;

        Impl(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth,
             String operatorUser, String operatorPassword, boolean enforce) {
            this.identityService = identityService;
            this.gatewayAuth = gatewayAuth;
            this.enforce = enforce;
            this.operatorBasic = (operatorUser != null && !operatorUser.isBlank())
                    ? "Basic " + Base64.getEncoder().encodeToString(
                            (operatorUser + ":" + operatorPassword).getBytes(StandardCharsets.UTF_8))
                    : null;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            // Lenient posture: no work at all, dev/qa behave exactly as before.
            if (!enforce) {
                chain.doFilter(request, response);
                return;
            }
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;

            if (isAllowlisted(req)) {
                chain.doFilter(request, response);
                return;
            }

            String principal = resolvePrincipal(req.getHeader("Authorization"));
            if (principal != null) {
                req.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
                chain.doFilter(request, response);
                return;
            }

            log.warn("ApiDefaultDenyFilter: denied unauthenticated {} {}", req.getMethod(), req.getRequestURI());
            ApiError.write(res, HttpServletResponse.SC_UNAUTHORIZED,
                    "Unauthorized", "Authentication required");
        }

        /** Paths that may proceed WITHOUT a credential. */
        private boolean isAllowlisted(HttpServletRequest req) {
            if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                return true; // CORS preflight
            }
            String path = req.getRequestURI();
            if (path == null) {
                return false;
            }
            if (path.startsWith("/api/public/")) {
                return true; // token / HMAC authenticated by design
            }
            if (path.startsWith("/api/internal/")) {
                return true; // fail-closed InternalAuthFilter (order 0) already gated it
            }
            // Global capability catalog: GET is intentionally public; writes are operator-guarded.
            boolean catalogRead = "GET".equalsIgnoreCase(req.getMethod())
                    && (path.equals("/api/capabilities") || path.startsWith("/api/capabilities/"));
            return catalogRead;
        }

        /** Resolve the caller from the Authorization header, or null if none is valid. */
        private String resolvePrincipal(String authHeader) {
            if (authHeader == null) {
                return null;
            }
            String trimmed = authHeader.trim();
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("bearer ")) {
                return gatewayAuth.authenticate(trimmed.substring(7).trim()); // verified sub, or null
            }
            if (lower.startsWith("basic ")) {
                // Operator credential (server-to-server), constant-time.
                if (operatorBasic != null && constantTimeEquals(trimmed, operatorBasic)) {
                    return "operator";
                }
                // Engine username/password.
                try {
                    String dec = new String(Base64.getDecoder().decode(trimmed.substring(6).trim()),
                            StandardCharsets.UTF_8);
                    int c = dec.indexOf(':');
                    if (c >= 0 && identityService.checkPassword(dec.substring(0, c), dec.substring(c + 1))) {
                        return dec.substring(0, c);
                    }
                } catch (IllegalArgumentException ignored) {
                    // malformed Base64 → unauthenticated
                }
            }
            return null;
        }

        private static boolean constantTimeEquals(String a, String b) {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }
    }
}
