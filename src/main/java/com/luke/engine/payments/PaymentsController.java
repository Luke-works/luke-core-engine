package com.luke.engine.payments;

import com.luke.engine.capability.access.CapabilityAccessService;
import com.luke.engine.capability.access.CapabilityLevel;
import com.luke.engine.config.ApiCallerResolver;
import com.luke.engine.tenant.TenantOwnership;
import java.util.LinkedHashMap;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant's payment settings: which Stripe account (if any) the workspace's forms charge into.
 *
 * <p>Any MEMBER may read the status — the form builder needs to know whether payments are usable.
 * Connecting, refreshing and disconnecting are for the workspace OWNER only: they decide where the
 * tenant's money goes. The caller is resolved from the {@code Authorization} credential and checked
 * against the tenant in {@code X-Tenant-Id}; the header alone proves nothing.
 *
 * <p>The OAuth return is not an endpoint here. Stripe sends the owner back to the app's payments page,
 * which posts the {@code code} + {@code state} to {@code /connect/complete} with the owner's own
 * credentials — so the round-trip is completed by an authenticated owner of the tenant that started it.
 * That tenant is read from the pending state, not the request: the page reloads on the way back and
 * may come up in a different workspace. The response names it so the page can switch.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentsController {

    private final PaymentAccountService accounts;
    private final FormPaymentService payments;
    private final ApiCallerResolver callers;
    private final IdentityService identity;
    private final CapabilityAccessService access;

    public PaymentsController(PaymentAccountService accounts, FormPaymentService payments, ApiCallerResolver callers,
                              IdentityService identity, CapabilityAccessService access) {
        this.accounts = accounts;
        this.payments = payments;
        this.callers = callers;
        this.identity = identity;
        this.access = access;
    }

    public record CompleteBody(String code, String state) {}

    @GetMapping("/account")
    public Map<String, Object> account(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String userId = requireMember(auth, tenantId);
        return accounts.view(tenantId, TenantOwnership.isOwner(identity, userId, tenantId));
    }

    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String userId = requireOwner(auth, tenantId);
        return Map.of("url", accounts.startConnect(tenantId, userId));
    }

    @PostMapping("/connect/complete")
    public Map<String, Object> complete(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                        @RequestBody(required = false) CompleteBody body) {
        String state = body == null ? null : body.state();
        String startedFor = accounts.connectStateTenant(state).orElse(tenantId);
        String userId = requireOwner(auth, startedFor);
        Map<String, Object> out = new LinkedHashMap<>(
                accounts.completeConnect(startedFor, userId, body == null ? null : body.code(), state));
        out.put("tenantId", startedFor);
        return out;
    }

    @PostMapping("/account/refresh")
    public Map<String, Object> refresh(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        requireOwner(auth, tenantId);
        return accounts.refresh(tenantId);
    }

    @DeleteMapping("/account")
    public Map<String, Object> disconnect(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String userId = requireOwner(auth, tenantId);
        return accounts.disconnect(tenantId, userId);
    }

    /** A submission's charge, for the responses view. Never includes a client secret. */
    @GetMapping("/submissions/{instanceId}")
    public Map<String, Object> submission(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                          @PathVariable String instanceId) {
        String userId = requireMember(auth, tenantId);
        // Submission data: the same bar as reading the submission itself.
        if (!access.permits(tenantId, userId, "FORMS", CapabilityLevel.Action.READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to form submissions");
        }
        return payments.staffView(tenantId, instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This submission has no payment."));
    }

    /* ── authorization ────────────────────────────────────────────────────── */

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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the workspace owner can manage payments");
        }
        return userId;
    }
}
