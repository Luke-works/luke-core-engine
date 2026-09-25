package com.luke.engine.ai;

import com.luke.engine.config.ApiCallerResolver;
import com.luke.engine.tenant.TenantOwnership;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The workspace's AI settings: which provider account the assistant runs on.
 *
 * <p>Any MEMBER may read the status — every AI panel in the app needs to know whether the
 * assistant is usable before it offers itself. Connecting, changing the model and disconnecting
 * are for the workspace OWNER only: they are pasting a credential that gets billed. The caller is
 * resolved from the {@code Authorization} credential and checked against the tenant in
 * {@code X-Tenant-Id}; the header alone proves nothing.
 *
 * <p>No endpoint here ever returns the key. The most any response carries is its last four
 * characters, so a human can tell which of their keys is in use.
 *
 * <p>There is deliberately <b>no plan gate</b>. Under bring-your-own-key the workspace pays its
 * own provider and Lukeflow carries no inference cost, so there is nothing to meter and nothing
 * to sell back to them.
 */
@RestController
@RequestMapping("/api/ai")
public class AiProviderController {

    private final AiProviderService providers;
    private final AiProperties props;
    private final ApiCallerResolver callers;
    private final IdentityService identity;
    private final AiModelRanking ranking;

    public AiProviderController(AiProviderService providers, AiProperties props,
                                ApiCallerResolver callers, IdentityService identity,
                                AiModelRanking ranking) {
        this.providers = providers;
        this.props = props;
        this.callers = callers;
        this.identity = identity;
        this.ranking = ranking;
    }

    public record ConnectBody(String provider, String apiKey, String model) {}

    public record ModelBody(String provider, String model) {}

    /**
     * Whether this deployment has an agent fleet at all, and what a workspace could connect to.
     * Readable by any member: the connect page needs it before anything is connected.
     */
    @GetMapping("/config")
    public Map<String, Object> config(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        requireMember(auth, tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.enabled());
        out.put("providers", AiProviderCatalog.all().stream().map(p -> Map.of(
                "id", p.id(), "label", p.label(),
                "defaultModel", p.defaultModel(), "keyPrefix", p.keyPrefix(),
                "consoleUrl", p.consoleUrl())).toList());
        return out;
    }

    @GetMapping("/provider")
    public Map<String, Object> get(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String userId = requireMember(auth, tenantId);
        return withFlags(providers.view(tenantId), userId, tenantId);
    }

    @PutMapping("/provider")
    public Map<String, Object> connect(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @RequestBody ConnectBody body) {
        String userId = requireOwner(auth, tenantId);
        requireEnabled();
        return withFlags(providers.connect(tenantId, userId, body.provider(), body.apiKey(), body.model()),
                userId, tenantId);
    }

    /** Re-check one connected provider's stored key against it. */
    @PostMapping("/provider/{provider}/verify")
    public Map<String, Object> verify(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                      @PathVariable String provider) {
        String userId = requireOwner(auth, tenantId);
        requireEnabled();
        return withFlags(providers.verify(tenantId, provider), userId, tenantId);
    }

    /** Which connected provider a turn uses when the person running it hasn't picked one. */
    @PutMapping("/provider/{provider}/default")
    public Map<String, Object> setDefault(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                          @PathVariable String provider) {
        String userId = requireOwner(auth, tenantId);
        requireEnabled();
        return withFlags(providers.setPreferred(tenantId, userId, provider), userId, tenantId);
    }

    /**
     * The models this workspace's key may use — read live from the provider.
     *
     * <p>Any MEMBER, not just the owner: everyone picks their own model, so everyone needs the
     * list. It reveals model names, never the key.
     */
    @GetMapping("/provider/models")
    public Map<String, Object> models(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                      @RequestParam(value = "agent", required = false) String agent) {
        requireMember(auth, tenantId);
        requireEnabled();
        // Each entry carries `chat`: whether an agent turn could run on it. The provider lists
        // every modality the account can reach, and a form builder cannot run on a
        // speech-to-text model.
        List<Map<String, Object>> described =
                AiProviderService.describe(providers.modelsForMembers(tenantId));
        if (agent == null || agent.isBlank()) return Map.of("models", described);

        // …and, when the caller says what the models are FOR, which of them are worth putting
        // first. Asked per provider because a recommendation is only meaningful against what
        // that account can reach, and because the seed the fleet falls back to is per provider.
        //
        // Best-effort throughout: an unreachable fleet leaves every model simply unmarked,
        // which is the list as it was before. Nobody loses a model because an opinion about it
        // was unavailable.
        Map<String, List<String>> offeredByProvider = described.stream().collect(
                java.util.stream.Collectors.groupingBy(
                        m -> String.valueOf(m.get("provider")),
                        java.util.stream.Collectors.mapping(
                                m -> String.valueOf(m.get("id")), java.util.stream.Collectors.toList())));
        Map<String, List<String>> picks = new java.util.LinkedHashMap<>();
        offeredByProvider.forEach((provider, offered) ->
                picks.put(provider, ranking.recommended(agent, provider, offered)));

        List<Map<String, Object>> out = described.stream().map(m -> {
            List<String> best = picks.getOrDefault(String.valueOf(m.get("provider")), List.of());
            int at = best.indexOf(String.valueOf(m.get("id")));
            if (at < 0) return m;
            Map<String, Object> copy = new java.util.LinkedHashMap<>(m);
            copy.put("recommended", true);
            // Its place in the order, so the UI can keep "best first" without re-deriving it.
            copy.put("rank", at);
            return copy;
        }).toList();
        return Map.of("models", out);
    }

    /** This person's own model choice — what their turns run on. */
    @GetMapping("/preference")
    public Map<String, Object> preference(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String userId = requireMember(auth, tenantId);
        return withFlags(providers.preference(tenantId, userId), userId, tenantId);
    }

    /**
     * Choose the model THIS person's turns run on. Blank follows the workspace's setting.
     *
     * <p>Member-level on purpose: the key is the owner's to manage, the model is each person's
     * own. Nobody can change anyone else's — the user is taken from the credential, never from
     * the request body.
     */
    @PutMapping("/preference")
    public Map<String, Object> chooseMyModel(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                             @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                             @RequestBody ModelBody body) {
        String userId = requireMember(auth, tenantId);
        requireEnabled();
        return withFlags(providers.chooseMyModel(tenantId, userId, body.provider(), body.model()),
                userId, tenantId);
    }

    /** Change one provider's workspace model without re-pasting its key. */
    @PutMapping("/provider/{provider}/model")
    public Map<String, Object> chooseModel(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                           @PathVariable String provider,
                                           @RequestBody ModelBody body) {
        String userId = requireOwner(auth, tenantId);
        requireEnabled();
        return withFlags(providers.chooseModel(tenantId, userId, provider, body.model()), userId, tenantId);
    }

    @DeleteMapping("/provider/{provider}")
    public Map<String, Object> disconnect(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                          @PathVariable String provider) {
        String userId = requireOwner(auth, tenantId);
        return withFlags(providers.disconnect(tenantId, userId, provider), userId, tenantId);
    }


    /**
     * Every response from this controller carries the same two flags the UI needs to decide what
     * to render: whether this deployment has AI at all, and whether this caller may change it.
     *
     * <p>They were attached only on the GETs, so a successful PUT returned a view missing both —
     * and a UI that replaces its state with the response concluded the feature had vanished.
     * Attached here so a new endpoint cannot forget.
     */
    private Map<String, Object> withFlags(Map<String, Object> view, String userId, String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>(view);
        out.put("enabled", props.enabled());
        out.put("canManage", TenantOwnership.isOwner(identity, userId, tenantId));
        return out;
    }

    /* ── authorization ────────────────────────────────────────────────────── */

    private void requireEnabled() {
        if (!props.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "AI features aren't available on this Lukeflow environment yet.");
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

    private String requireOwner(String auth, String tenantId) {
        String userId = requireMember(auth, tenantId);
        if (!TenantOwnership.isOwner(identity, userId, tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the workspace owner can manage the AI provider");
        }
        return userId;
    }
}
