package com.luke.engine.ai;

import com.luke.engine.capability.access.CapabilityAccessService;
import com.luke.engine.capability.signature.ClientIp;
import com.luke.engine.capability.access.CapabilityLevel;
import com.luke.engine.branding.PlanFeatures;
import com.luke.engine.config.ApiCallerResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The only door to the agent fleet.
 *
 * <p>The browser used to call luke-agents directly, with the tenant asserted by a header the
 * client set. That was survivable while one platform key served everyone — a spoofed tenant just
 * mislabelled a budget bucket. Under bring-your-own-key it would be credential theft: name
 * another workspace and spend their key. So the fleet moves behind this controller, which
 * authenticates the user, checks they may act for the tenant, and only then attaches that
 * workspace's decrypted key to the turn.
 *
 * <p>Three consequences worth stating plainly:
 * <ul>
 *   <li>The key never reaches the browser. It is decrypted here and travels one hop, server to
 *       server, on a request the client cannot see or influence.</li>
 *   <li>Nothing from the caller's request is forwarded except the JSON body — not their
 *       {@code Authorization} header, not their tenant header. Every header the fleet trusts is
 *       set here from what we verified.</li>
 *   <li>The agent slug and operation are matched against a strict pattern before they touch a
 *       URL, so no caller can steer the outbound request at another path.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/agents")
public class AgentProxyController {

    private static final Logger log = LoggerFactory.getLogger(AgentProxyController.class);

    /** Bounds what can reach the outbound URL. No dots, no slashes, so no traversal. */
    private static final Pattern SEGMENT = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");

    /** Generous for a form schema, far below anything that would hurt us. */
    private static final int MAX_BODY_BYTES = 1_000_000;

    /**
     * An agent turn blocks its Tomcat worker for the whole LLM call, so bound how many can do
     * that at once. Before this feature, LLM latency never touched an engine thread — the
     * browser called the fleet directly. Unbounded, a single workspace firing concurrent turns
     * pins the shared worker pool (Tomcat's default is 200) for up to the turn timeout, and
     * every other tenant's form submission and capability check queues behind it.
     *
     * <p>Fail fast with 429 rather than queueing: a caller that waits still holds a thread,
     * which is the thing being rationed.
     */
    private static final int MAX_IN_FLIGHT = 24;

    /** No one workspace may take more than this share of the budget above. */
    private static final int MAX_IN_FLIGHT_PER_TENANT = 4;

    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    private final ConcurrentHashMap<String, Semaphore> perTenant = new ConcurrentHashMap<>();

    /**
     * Which capability an agent belongs to. An agent turn edits that capability's content and
     * spends the workspace's money, so the bar is the capability's ordinary WRITE — the same
     * bar as making the change by hand. An unlisted slug is refused rather than defaulted:
     * a new agent should have to say who may use it.
     */
    private static final Map<String, String> AGENT_CAPABILITY = Map.of(
            "form", "FORMS",
            "email", "EMAIL",
            "workflow", "WORKFLOW",
            "sentiment", "FORMS");

    private final AiProviderService providers;
    private final AiProperties props;
    private final ApiCallerResolver callers;
    private final IdentityService identity;
    private final CapabilityAccessService access;
    private final PlanFeatures plans;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)  // a redirect must never carry the key elsewhere
            .build();

    public AgentProxyController(AiProviderService providers, AiProperties props, ApiCallerResolver callers,
                                IdentityService identity, CapabilityAccessService access, PlanFeatures plans) {
        this.providers = providers;
        this.props = props;
        this.callers = callers;
        this.identity = identity;
        this.access = access;
        this.plans = plans;
    }

    @PostMapping(value = "/{slug}/{op}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> call(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @PathVariable String slug,
                                       @PathVariable String op,
                                       @RequestBody(required = false) String body,
                                       HttpServletRequest http) {
        if (!props.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "AI features aren't available on this Lukeflow environment yet.");
        }
        if (!SEGMENT.matcher(slug).matches() || !SEGMENT.matcher(op).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown AI operation.");
        }
        String capability = AGENT_CAPABILITY.get(slug);
        if (capability == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown AI agent: " + slug);
        }

        String userId = requireMember(auth, tenantId);
        if (!access.permits(tenantId, userId, capability, CapabilityLevel.Action.WRITE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You don't have access to use the assistant here.");
        }

        String clientIp = ClientIp.resolve(http);
        String payload = body == null ? "{}" : body;
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "That's too much content for one request.");
        }

        // No provider connected → the single 402 the UI turns into "Connect your AI provider".
        AiProviderService.Resolved credential = providers.resolve(tenantId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Connect an AI provider to use the assistant."));

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(props.base() + "/agents/" + slug + "/" + op))
                .timeout(Duration.ofMillis(props.timeoutMs()))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                // The tenant the fleet sees is the one WE verified, never one a client claimed.
                .header("X-Tenant-Id", tenantId)
                // The fleet's per-caller rate limit keys on tenant + client IP. Every request now
                // originates here, so without this the IP is a constant and RATE_LIMIT_MAX quietly
                // becomes one shared bucket for the whole workspace instead of a per-user cap.
                .header("X-Forwarded-For", clientIp)
                // Sizes that workspace's daily token cap. Resolved from the stored plan, NOT from
                // the browser: a client-asserted tier is a client-chosen spend limit. (It also
                // can't ride as a browser header any more — the gateway's CORS allowlist is
                // Authorization/Content-Type/Accept/X-Tenant-Id, so sending it would fail every
                // preflight and block all AI calls.)
                .header("X-Tenant-Tier", plans.tierId(tenantId))
                .header("X-AI-Provider", credential.provider())
                .header("X-AI-Key", credential.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (credential.model() != null && !credential.model().isBlank()) {
            req.header("X-AI-Model", credential.model());
        }
        if (props.serviceKey() != null) {
            req.header("X-Agents-Key", props.serviceKey());
        }

        HttpResponse<String> res = send(req.build(), tenantId, slug, op);

        // The fleet tells us WHY a turn failed; only it knows, and only we can act on it,
        // because we are the ones holding the key.
        String signal = res.headers().firstValue("X-AI-Credential").orElse("");
        if ("invalid".equalsIgnoreCase(signal)) {
            // Pass the key we actually used: by the time a 90-second turn fails, the workspace
            // may already have reconnected with a good one, and the old key's failure must not
            // disable the new one. "exhausted" is deliberately NOT handled here — that account
            // is out of credit, which the workspace fixes with their provider, not by
            // reconnecting, and disconnecting them would only hide the real message.
            providers.markInvalid(tenantId, "Your AI provider rejected this key. Reconnect your provider.",
                    credential.apiKey());
        }

        // Pass the fleet's own status and body through: its error messages are already written
        // for a human and deliberately free of provider detail.
        return ResponseEntity.status(res.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(res.body());
    }

    /**
     * The one blocking call, under a global and a per-tenant budget.
     *
     * <p>Both permits are released in {@code finally}, including on the 429 path, so a refused
     * caller never leaks the slot it did not get.
     */
    private HttpResponse<String> send(HttpRequest request, String tenantId, String slug, String op) {
        Semaphore tenantSlots = perTenant.computeIfAbsent(tenantId, t -> new Semaphore(MAX_IN_FLIGHT_PER_TENANT));
        if (!tenantSlots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many assistant requests at once. Finish one and try again.");
        }
        try {
            if (!inFlight.tryAcquire()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "The assistant is busy right now. Please try again shortly.");
            }
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString());
            } finally {
                inFlight.release();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ai: agent {}/{} unreachable for tenant {}: {}", slug, op, tenantId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service is temporarily unavailable. Please try again shortly.");
        } finally {
            tenantSlots.release();
            // One tiny Semaphore per tenant; drop idle ones if the map ever gets silly,
            // mirroring MinionRateLimiter's stale-window sweep.
            if (perTenant.size() > 10_000) {
                perTenant.values().removeIf(sem -> sem.availablePermits() == MAX_IN_FLIGHT_PER_TENANT);
            }
        }
    }

    private String requireMember(String auth, String tenantId) {
        String userId = callers.resolve(auth, true);
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid credentials required");
        if (tenantId == null || tenantId.isBlank()
                || identity.createTenantQuery().tenantId(tenantId).userMember(userId).count() == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this workspace");
        }
        return userId;
    }
}
