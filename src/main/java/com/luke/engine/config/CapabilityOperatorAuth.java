package com.luke.engine.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * The operator credential core-engine presents to capability-engine on its
 * server-to-server admin calls (tenant subscriptions and per-user grants under
 * {@code /api/tenants/**}). capability-engine requires this credential so those
 * privileged endpoints can't be hit directly — the UI only ever reaches them
 * through this engine, after tenant-admin authorization.
 *
 * <p>Configured via {@code CAPABILITY_OPERATOR_USER} / {@code CAPABILITY_OPERATOR_PASSWORD}
 * (the same pair capability-engine verifies). If unset, no header is sent — which
 * matches capability-engine's pass-through-when-unconfigured behavior for local dev.
 */
@Component
public class CapabilityOperatorAuth {

    private final String basic; // "Basic base64(user:pass)" or null when unconfigured

    public CapabilityOperatorAuth(
            @Value("${luke.capabilities.operator.user:}") String user,
            @Value("${luke.capabilities.operator.password:}") String password) {
        this.basic = (user != null && !user.isBlank())
                ? "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8))
                : null;
    }

    /** Add the operator Authorization header to {@code headers} (no-op when unconfigured). */
    public HttpHeaders apply(HttpHeaders headers) {
        if (basic != null) headers.set(HttpHeaders.AUTHORIZATION, basic);
        return headers;
    }

    /** Fresh headers carrying just the operator credential. */
    public HttpHeaders headers() {
        return apply(new HttpHeaders());
    }

    /** A bodiless entity carrying the operator credential, for GET/PUT/DELETE. */
    public HttpEntity<Void> entity() {
        return new HttpEntity<>(headers());
    }
}
