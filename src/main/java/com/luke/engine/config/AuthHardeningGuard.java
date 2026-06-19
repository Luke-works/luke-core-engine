package com.luke.engine.config;

import com.luke.engine.capability.access.GatewayTokenVerifier;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Guards against shipping the capability auth layers in their FAIL-OPEN state (#56).
 * Two layers pass requests through when their config is absent:
 * <ul>
 *   <li>{@link GatewayAuthFilter} — disabled when {@code luke.auth.gateway.jwks-url}
 *       is unset (then it trusts client {@code X-User-Id}/{@code X-Tenant-Id});</li>
 *   <li>{@link OperatorAuthFilter} — disabled when {@code CAPABILITY_OPERATOR_USER}
 *       is unset (then {@code /api/tenants,/api/users,/api/capabilities} writes are open).</li>
 * </ul>
 * (The third internal layer already fails CLOSED via #41.)
 *
 * <p>Enforcement is OPT-IN via {@code luke.auth.require-strong-auth=true} (default
 * false) rather than keyed off the prod profile, because dev/qa does not currently
 * set the operator credential — a profile-based fail-fast would crash it. PROD
 * configures both layers AND sets this flag; otherwise the gaps are logged loudly.
 */
@Component
public class AuthHardeningGuard {

    private static final Logger log = LoggerFactory.getLogger(AuthHardeningGuard.class);

    private final boolean gatewayEnabled;
    private final boolean operatorConfigured;
    private final boolean requireStrongAuth;

    public AuthHardeningGuard(
            GatewayTokenVerifier gatewayVerifier,
            @Value("${luke.auth.operator.user:}") String operatorUser,
            @Value("${luke.auth.require-strong-auth:false}") boolean requireStrongAuth) {
        this.gatewayEnabled = gatewayVerifier.isEnabled();
        this.operatorConfigured = StringUtils.hasText(operatorUser);
        this.requireStrongAuth = requireStrongAuth;
    }

    @PostConstruct
    void verify() {
        List<String> open = openAuthLayers(gatewayEnabled, operatorConfigured);
        if (open.isEmpty()) {
            return;
        }
        if (requireStrongAuth) {
            throw new IllegalStateException(
                    "Refusing to start: auth layer(s) fail OPEN: " + open
                    + ". Configure them, or unset luke.auth.require-strong-auth for local dev.");
        }
        log.warn("Auth layer(s) currently FAIL OPEN (local-dev posture): {}. Configure them and set "
                + "luke.auth.require-strong-auth=true in production.", open);
    }

    /** Which configurable auth layers are in their fail-open (pass-through) state. */
    static List<String> openAuthLayers(boolean gatewayEnabled, boolean operatorConfigured) {
        List<String> open = new ArrayList<>();
        if (!gatewayEnabled) open.add("gateway token verification (luke.auth.gateway.jwks-url)");
        if (!operatorConfigured) open.add("operator credential (CAPABILITY_OPERATOR_USER/PASSWORD)");
        return open;
    }
}
