package com.luke.engine.capability.minion;

import com.luke.engine.capability.form.EmbedFormResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The PUBLIC, UNauthenticated secure minion proxy for embedded forms. Reached as
 * {@code POST /api/public/minions/{token}/{minion}} — the same gateway-forwarded, no-user path as the
 * embed render/submit. The signed embed token resolves the tenant (it IS the auth, like
 * {@code FormEmbedController}); only minions that explicitly {@link Minion#publicAllowed() opt in} are
 * reachable. Rate-limited per IP AND per token so a public form can't be turned into a free proxy to a
 * metered provider API.
 */
@RestController
@RequestMapping("/api/public/minions")
public class PublicMinionController {

    private static final int MAX_PER_TOKEN_PER_MIN = 30;
    private static final int MAX_PER_IP_PER_MIN = 60;

    private final EmbedFormResolver resolver;
    private final MinionRegistry registry;
    private final MinionRateLimiter rateLimiter;

    public PublicMinionController(EmbedFormResolver resolver, MinionRegistry registry, MinionRateLimiter rateLimiter) {
        this.resolver = resolver;
        this.registry = registry;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{token}/{minion}")
    public Object call(@PathVariable String token,
                       @PathVariable String minion,
                       @RequestBody(required = false) Map<String, Object> params,
                       HttpServletRequest request) {
        // Per-IP cap first — bounds ALL public-minion traffic from one source across every token.
        rateLimiter.check("ip:" + clientIp(request) + ":" + minion, MAX_PER_IP_PER_MIN);
        // The token resolves the tenant (404 on a forged/revoked token — never leaks validity).
        String tenantId = resolver.resolveTenant(token);
        Minion m = registry.get(minion);
        if (m == null || !m.publicAllowed()) {
            // Same 404 for "unknown" and "exists but internal-only" — don't reveal which minions exist.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown operation.");
        }
        rateLimiter.check("t:" + token + ":" + minion, MAX_PER_TOKEN_PER_MIN);
        return m.handle(tenantId, params != null ? params : Map.of());
    }

    /** Best-effort client IP for rate-limiting. Prefers X-Real-Client-IP — the gateway resolves the
     *  true client IP spoof-resistantly (trusted-proxy-hops from the right) and stamps it, stripping any
     *  client-supplied value first, so when this surface is reached only through the gateway it is
     *  authoritative and NOT spoofable. Falls back to the left-most X-Forwarded-For hop, then X-Real-IP,
     *  then the socket. Not used for authz — spoofing only changes one's own rate bucket. */
    private static String clientIp(HttpServletRequest req) {
        String vouched = req.getHeader("X-Real-Client-IP");
        if (vouched != null && !vouched.isBlank()) return vouched.trim();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String remote = req.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
