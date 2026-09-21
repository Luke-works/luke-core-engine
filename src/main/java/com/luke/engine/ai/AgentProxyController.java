package com.luke.engine.ai;

import com.luke.engine.capability.access.CapabilityAccessService;
import com.luke.engine.capability.access.CapabilityLevel;
import com.luke.engine.config.ApiCallerResolver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
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
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)  // a redirect must never carry the key elsewhere
            .build();

    public AgentProxyController(AiProviderService providers, AiProperties props, ApiCallerResolver callers,
                                IdentityService identity, CapabilityAccessService access) {
        this.providers = providers;
        this.props = props;
        this.callers = callers;
        this.identity = identity;
        this.access = access;
    }

    @PostMapping(value = "/{slug}/{op}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> call(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @PathVariable String slug,
                                       @PathVariable String op,
                                       @RequestBody(required = false) String body) {
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
                .header("X-AI-Provider", credential.provider())
                .header("X-AI-Key", credential.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (credential.model() != null && !credential.model().isBlank()) {
            req.header("X-AI-Model", credential.model());
        }
        if (props.serviceKey() != null) {
            req.header("X-Agents-Key", props.serviceKey());
        }

        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ai: agent {}/{} unreachable for tenant {}: {}", slug, op, tenantId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service is temporarily unavailable. Please try again shortly.");
        }

        // The fleet tells us WHY a turn failed; only it knows, and only we can act on it,
        // because we are the ones holding the key.
        String signal = res.headers().firstValue("X-AI-Credential").orElse("");
        if ("invalid".equalsIgnoreCase(signal)) {
            providers.markInvalid(tenantId, "Your AI provider rejected this key. Reconnect your provider.");
        }

        // Pass the fleet's own status and body through: its error messages are already written
        // for a human and deliberately free of provider detail.
        return ResponseEntity.status(res.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(res.body());
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
