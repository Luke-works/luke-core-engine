package com.luke.engine.config;

import com.luke.engine.capability.access.GatewayTokenVerifier;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
 * <p>Enforcement is fail-fast when EITHER the dedicated {@code prod} profile is active
 * ({@link StrictProfile}) OR the opt-in {@code luke.auth.require-strong-auth=true} flag
 * is set. It is deliberately NOT keyed off the {@code postgres} profile, because dev/qa
 * run {@code postgres} without the operator credential — a postgres-based fail-fast would
 * crash them. PROD runs {@code postgres,prod} once both layers are configured; otherwise
 * the gaps are logged loudly.
 */
@Component
public class AuthHardeningGuard {

    private static final Logger log = LoggerFactory.getLogger(AuthHardeningGuard.class);

    private final boolean gatewayEnabled;
    private final boolean operatorConfigured;
    private final boolean requireStrongAuth;

    @Autowired
    public AuthHardeningGuard(
            GatewayTokenVerifier gatewayVerifier,
            @Value("${luke.auth.operator.user:}") String operatorUser,
            @Value("${luke.auth.require-strong-auth:false}") boolean requireStrongAuth,
            Environment environment) {
        // The prod profile forces strictness even if the opt-in flag is left unset.
        this(gatewayVerifier, operatorUser, requireStrongAuth || StrictProfile.isActive(environment));
    }

    /** Test-friendly constructor: the effective strict flag is already resolved. */
    AuthHardeningGuard(
            GatewayTokenVerifier gatewayVerifier,
            String operatorUser,
            boolean requireStrongAuth) {
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
                    + ". Configure them, or drop the 'prod' profile / luke.auth.require-strong-auth for local dev.");
        }
        log.warn("Auth layer(s) currently FAIL OPEN (local-dev posture): {}. Configure them; the 'prod' profile "
                + "(or luke.auth.require-strong-auth=true) then enforces this at startup.", open);
    }

    /** Which configurable auth layers are in their fail-open (pass-through) state. */
    static List<String> openAuthLayers(boolean gatewayEnabled, boolean operatorConfigured) {
        List<String> open = new ArrayList<>();
        if (!gatewayEnabled) open.add("gateway token verification (luke.auth.gateway.jwks-url)");
        if (!operatorConfigured) open.add("operator credential (CAPABILITY_OPERATOR_USER/PASSWORD)");
        return open;
    }
}
