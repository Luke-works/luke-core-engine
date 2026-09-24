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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    public AiProviderController(AiProviderService providers, AiProperties props,
                                ApiCallerResolver callers, IdentityService identity) {
        this.providers = providers;
        this.props = props;
        this.callers = callers;
        this.identity = identity;
    }

    public record ConnectBody(String provider, String apiKey, String model) {}

    public record ModelBody(String model) {}

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
        Map<String, Object> out = new LinkedHashMap<>(providers.view(tenantId));
        out.put("enabled", props.enabled());
        // Only an owner sees a "Connect" button, so tell the page which it is dealing with.
        out.put("canManage", TenantOwnership.isOwner(identity, userId, tenantId));
        return out;
    }

    @PutMapping("/provider")
    public Map<String, Object> connect(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @RequestBody ConnectBody body) {
        String userId = requireOwner(auth, tenantId);
        requireEnabled();
        return providers.connect(tenantId, userId, body.provider(), body.apiKey(), body.model());
    }

    /** Re-check the stored key against the provider. */
    @PostMapping("/provider/verify")
    public Map<String, Object> verify(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        requireOwner(auth, tenantId);
        requireEnabled();
        return providers.verify(tenantId);
    }

    /**
     * The models this workspace's key may use — read live from the provider.
     *
     * <p>Any MEMBER, not just the owner: everyone picks their own model, so everyone needs the
     * list. It reveals model names, never the key.
     */
    @GetMapping("/provider/models")
    public Map<String, Object> models(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        requireMember(auth, tenantId);
        requireEnabled();
        return Map.of("models", providers.modelsForMembers(tenantId));
    }

    /** This person's own model choice — what their turns run on. */
    @GetMapping("/preference")
    public Map<String, Object> preference(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String userId = requireMember(auth, tenantId);
        Map<String, Object> out = new LinkedHashMap<>(providers.preference(tenantId, userId));
        out.put("enabled", props.enabled());
        return out;
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
        return providers.chooseMyModel(tenantId, userId, body.model());
    }

    /** Change the model without re-pasting the key. */
    @PutMapping("/provider/model")
    public Map<String, Object> chooseModel(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                           @RequestBody ModelBody body) {
        String userId = requireOwner(auth, tenantId);
        requireEnabled();
        return providers.chooseModel(tenantId, userId, body.model());
    }

    @DeleteMapping("/provider")
    public Map<String, Object> disconnect(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String userId = requireOwner(auth, tenantId);
        return providers.disconnect(tenantId, userId);
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
