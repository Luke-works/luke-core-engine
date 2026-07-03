package com.luke.engine.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Prod fail-fast for edge-facing defaults that are safe in dev but dangerous in production
 * (mirrors {@link InsecureKeyGuard} / {@link AuthHardeningGuard}). Two concerns:
 *
 * <ul>
 *   <li><b>CORS</b> — {@code luke.cors.allowed-origins} defaults to {@code http://localhost:*}
 *       ({@code SecurityConfig}). Left at the default in prod, the API would trust browser
 *       requests from any localhost origin; refuse to start so a missing {@code ALLOWED_ORIGINS}
 *       can't ship.</li>
 *   <li><b>Webhooks</b> — the Vapi and Nango verifiers accept unsigned calls unless their
 *       {@code webhook-require-*} flag is on. We only fail-fast when a webhook <em>secret is
 *       configured but not enforced</em> — an unambiguous misconfig — so a deployment that
 *       doesn't use phone/workflow (no secret set) is never blocked.</li>
 * </ul>
 *
 * <p>Enforced when the {@code prod} profile is active ({@link StrictProfile}) or the opt-in
 * {@code luke.security.require-strong-edge=true} flag is set; otherwise it logs a warning.
 */
@Component
public class EdgeHardeningGuard {

    private static final Logger log = LoggerFactory.getLogger(EdgeHardeningGuard.class);
    static final String DEV_CORS_DEFAULT = "http://localhost:*";

    private final String allowedOrigins;
    private final String vapiSecret;
    private final boolean vapiRequireSecret;
    private final String nangoSecret;
    private final boolean nangoRequireSignature;
    private final boolean requireStrong;

    @Autowired
    public EdgeHardeningGuard(
            @Value("${luke.cors.allowed-origins:http://localhost:*}") String allowedOrigins,
            @Value("${luke.phone.vapi.webhook-secret:}") String vapiSecret,
            @Value("${luke.phone.vapi.webhook-require-secret:false}") boolean vapiRequireSecret,
            @Value("${luke.workflow.nango.secret-key:}") String nangoSecret,
            @Value("${luke.workflow.nango.webhook-require-signature:false}") boolean nangoRequireSignature,
            @Value("${luke.security.require-strong-edge:false}") boolean requireStrong,
            Environment environment) {
        // The prod profile forces strictness even if the opt-in flag is left unset.
        this(allowedOrigins, vapiSecret, vapiRequireSecret, nangoSecret, nangoRequireSignature,
                requireStrong || StrictProfile.isActive(environment));
    }

    /** Test-friendly constructor: the effective strict flag is already resolved. */
    EdgeHardeningGuard(String allowedOrigins, String vapiSecret, boolean vapiRequireSecret,
            String nangoSecret, boolean nangoRequireSignature, boolean requireStrong) {
        this.allowedOrigins = allowedOrigins;
        this.vapiSecret = vapiSecret;
        this.vapiRequireSecret = vapiRequireSecret;
        this.nangoSecret = nangoSecret;
        this.nangoRequireSignature = nangoRequireSignature;
        this.requireStrong = requireStrong;
    }

    @PostConstruct
    void verify() {
        List<String> problems = new ArrayList<>();
        if (allowedOrigins == null || allowedOrigins.isBlank() || allowedOrigins.trim().equals(DEV_CORS_DEFAULT)) {
            problems.add("luke.cors.allowed-origins is the localhost dev default — set ALLOWED_ORIGINS to the "
                    + "real per-env origins");
        }
        // Only flag a webhook whose secret is configured but left unenforced (a real misconfig);
        // a feature that isn't used (no secret) is intentionally left alone.
        if (notBlank(vapiSecret) && !vapiRequireSecret) {
            problems.add("luke.phone.vapi.webhook-secret is set but webhook-require-secret=false — unsigned "
                    + "Vapi callbacks would be accepted");
        }
        if (notBlank(nangoSecret) && !nangoRequireSignature) {
            problems.add("luke.workflow.nango.secret-key is set but webhook-require-signature=false — unsigned "
                    + "Nango callbacks would be accepted");
        }
        if (problems.isEmpty()) {
            return;
        }
        if (requireStrong) {
            throw new IllegalStateException(
                    "Refusing to start: insecure edge default(s) in prod: " + problems
                    + ". Fix these before deploying (the 'prod' profile enforces this).");
        }
        log.warn("Insecure edge default(s) in use: {} — acceptable for local dev only. The 'prod' profile "
                + "(or luke.security.require-strong-edge=true) then enforces this at startup.", problems);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
