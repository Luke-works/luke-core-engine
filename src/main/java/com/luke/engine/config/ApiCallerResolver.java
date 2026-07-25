package com.luke.engine.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for resolving an {@code /api} caller from an {@code Authorization}
 * header (#20). Before this, ~7 controllers each re-implemented the same Base64/Bearer parsing with
 * subtle divergences (Basic-only vs Bearer+Basic, engine-user-must-exist vs not) — a fragile,
 * audit-unfriendly pattern. They now delegate the parsing here and keep only their own policy
 * (which error to throw, whether to require provisioning), so the credential handling is consistent
 * and lives in one tested place. {@link ApiDefaultDenyFilter} uses the same primitives.
 *
 * <p>Two credential kinds: a gateway act-as <b>Bearer</b> (verified by {@link GatewayJwtAuthenticator}
 * → the token's {@code sub}) and engine HTTP <b>Basic</b> (username/password checked against the
 * identity store). Every method returns the resolved userId or {@code null} — never throws — so each
 * caller decides how to signal failure.
 */
@Component
public class ApiCallerResolver {

    private final IdentityService identityService;
    private final GatewayJwtAuthenticator gatewayAuth;

    public ApiCallerResolver(IdentityService identityService, GatewayJwtAuthenticator gatewayAuth) {
        this.identityService = identityService;
        this.gatewayAuth = gatewayAuth;
    }

    /** The verified {@code sub} of a gateway Bearer token, or null (missing/invalid/not-a-Bearer). */
    public String bearerSub(String authHeader) {
        if (authHeader == null) {
            return null;
        }
        if (!authHeader.regionMatches(true, 0, "bearer ", 0, 7)) {
            return null;
        }
        return gatewayAuth.authenticate(authHeader.substring(7).trim());
    }

    /** The username of a valid engine HTTP-Basic credential, or null (missing/invalid/not-Basic). */
    public String basicUsername(String authHeader) {
        if (authHeader == null) {
            return null;
        }
        if (!authHeader.regionMatches(true, 0, "basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(authHeader.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon >= 0 && identityService.checkPassword(decoded.substring(0, colon), decoded.substring(colon + 1))) {
                return decoded.substring(0, colon);
            }
        } catch (IllegalArgumentException ignored) {
            // malformed Base64 → unauthenticated
        }
        return null;
    }

    /**
     * Resolve a caller from a Bearer or Basic credential — the common case for the self-service /
     * admin controllers. Returns the userId or null.
     *
     * @param requireProvisioned when true, a Bearer {@code sub} that has no engine user yet is
     *                           treated as unresolved (returns null) — used where the caller must
     *                           already exist in the engine (org-admin, audit). When false, a valid
     *                           token's {@code sub} is accepted as-is (first-org / delete-account).
     */
    public String resolve(String authHeader, boolean requireProvisioned) {
        String sub = bearerSub(authHeader);
        if (sub != null) {
            if (!requireProvisioned || userExists(sub)) {
                return sub;
            }
            return null;
        }
        return basicUsername(authHeader);
    }

    /** True when {@code userId} is a known engine user. */
    public boolean userExists(String userId) {
        return userId != null && identityService.createUserQuery().userId(userId).count() > 0;
    }
}
