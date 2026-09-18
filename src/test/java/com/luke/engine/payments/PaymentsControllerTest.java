package com.luke.engine.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.branding.TenantPlan;
import com.luke.engine.branding.TenantPlanRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.finos.fluxnova.bpm.engine.identity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The payment settings API: who may see and change where a workspace's money goes, and the Connect
 * OAuth round-trip's state binding.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:paymentsctl;DB_CLOSE_DELAY=-1",
        "luke.forms.outbox-enabled=false",
        "luke.payments.stripe.secret-key=sk_test_platform",
        "luke.payments.stripe.publishable-key=pk_test_platform",
        "luke.payments.stripe.connect-client-id=ca_test",
        "luke.payments.reconcile-enabled=false"
})
@AutoConfigureMockMvc
class PaymentsControllerTest {

    @TestConfiguration
    static class FakeStripeConfig {
        @Bean
        @Primary
        FakeStripeGateway fakeStripeGateway() {
            return new FakeStripeGateway();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired FakeStripeGateway stripe;
    @Autowired IdentityService identity;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentConnectStateRepository states;
    @Autowired TenantPlanRepository plans;
    @Autowired com.luke.engine.capability.capability.CapabilitySubscriptionRepository subscriptions;
    @Autowired com.luke.engine.capability.access.CapabilityGrantRepository grants;
    @Autowired FormPaymentRepository payments;

    private String tenant;
    private String owner;
    private String member;
    private String outsider;
    /** Each test connects its own Stripe account: accounts are shared across tests in this context. */
    private String acct;

    @BeforeEach
    void setUp() {
        stripe.reset();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenant = "tpay" + suffix;
        owner = "owner" + suffix;
        member = "member" + suffix;
        outsider = "outsider" + suffix;
        Tenant t = identity.newTenant(tenant);
        t.setName(tenant);
        identity.saveTenant(t);
        for (String u : new String[] {owner, member, outsider}) {
            User user = identity.newUser(u);
            user.setPassword("pw-" + u);
            identity.saveUser(user);
        }
        identity.createTenantUserMembership(tenant, owner);
        identity.createTenantUserMembership(tenant, member);
        Group ownerGroup = identity.newGroup("owner:" + tenant);
        ownerGroup.setName("Owners");
        ownerGroup.setType("WORKFLOW");
        identity.saveGroup(ownerGroup);
        identity.createMembership(owner, "owner:" + tenant);
        plans.save(new TenantPlan(tenant, "PRO"));
        acct = "acct_" + suffix;
        stripe.nextConnected = new StripeConnectGateway.ConnectedAccount(acct, false, "read_write");
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":pw-" + user).getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions as(String user, MockHttpServletRequestBuilder req) throws Exception {
        if (user != null) req = req.header("Authorization", basic(user));
        return mvc.perform(req.header("X-Tenant-Id", tenant));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String startState() throws Exception {
        String url = body(as(owner, post("/api/payments/connect")).andExpect(status().isOk())).path("url").asText();
        return url.substring(url.indexOf("state=") + "state=".length());
    }

    private ResultActions complete(String user, String code, String state) throws Exception {
        return as(user, post("/api/payments/connect/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("code", code, "state", state))));
    }

    @Test
    void membersCanReadStatus_butNotManage() throws Exception {
        JsonNode view = body(as(member, get("/api/payments/account")).andExpect(status().isOk()));
        assertThat(view.path("enabled").asBoolean()).isTrue();
        assertThat(view.path("canManage").asBoolean()).isFalse();
        assertThat(view.path("ready").asBoolean()).isFalse();
        assertThat(view.path("account").isNull()).isTrue();
        as(member, post("/api/payments/connect")).andExpect(status().isForbidden());
        as(member, delete("/api/payments/account")).andExpect(status().isForbidden());
        as(member, post("/api/payments/account/refresh")).andExpect(status().isForbidden());

        JsonNode ownerView = body(as(owner, get("/api/payments/account")).andExpect(status().isOk()));
        assertThat(ownerView.path("canManage").asBoolean()).isTrue();
    }

    @Test
    void outsidersAndAnonymousCallersAreRefused() throws Exception {
        as(null, get("/api/payments/account")).andExpect(status().isUnauthorized());
        as(outsider, get("/api/payments/account")).andExpect(status().isForbidden());
        as(outsider, post("/api/payments/connect")).andExpect(status().isForbidden());
        // A spoofed tenant header does not grant membership.
        mvc.perform(get("/api/payments/account").header("Authorization", basic(owner)).header("X-Tenant-Id", "someone-else"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOwnerConnectsAnAccount_andTheStateIsSingleUse() throws Exception {
        String state = startState();
        assertThat(states.findById(state)).isPresent();

        JsonNode view = body(complete(owner, "ac_code", state).andExpect(status().isOk()));
        assertThat(view.path("ready").asBoolean()).isTrue();
        assertThat(view.path("account").path("accountId").asText()).isEqualTo(acct);
        assertThat(view.path("tenantId").asText()).isEqualTo(tenant);
        assertThat(view.path("account").path("displayName").asText()).isEqualTo("Fake Co");
        PaymentAccount saved = accounts.findById(tenant).orElseThrow();
        assertThat(saved.getConnectedBy()).isEqualTo(owner);
        assertThat(saved.isChargesEnabled()).isTrue();
        assertThat(states.findById(state)).isEmpty();

        // Replaying the same code + state fails: the state is gone.
        complete(owner, "ac_code", state).andExpect(status().isBadRequest());
    }

    @Test
    void aStateStartedByAnotherOwnerOrTenantIsRefused() throws Exception {
        String state = startState();
        // A second owner of the same tenant can't finish someone else's round-trip…
        String owner2 = "owner2" + UUID.randomUUID().toString().substring(0, 6);
        User u = identity.newUser(owner2);
        u.setPassword("pw-" + owner2);
        identity.saveUser(u);
        identity.createTenantUserMembership(tenant, owner2);
        identity.createMembership(owner2, "owner:" + tenant);
        complete(owner2, "ac_code", state).andExpect(status().isBadRequest());
        assertThat(accounts.findById(tenant)).isEmpty();
        // …and the attempt consumed it, so the original owner must start again too.
        complete(owner, "ac_code", state).andExpect(status().isBadRequest());
    }

    @Test
    void anExpiredOrUnknownStateIsRefused() throws Exception {
        complete(owner, "ac_code", "never-issued").andExpect(status().isBadRequest());
        states.save(new PaymentConnectState("expired-" + tenant, tenant, owner, LocalDateTime.now().minusMinutes(1)));
        complete(owner, "ac_code", "expired-" + tenant).andExpect(status().isBadRequest());
        complete(owner, "", "whatever").andExpect(status().isBadRequest());
    }

    @Test
    void aLiveAccountCantBeConnectedToATestPlatform() throws Exception {
        stripe.nextConnected = new StripeConnectGateway.ConnectedAccount("acct_live", true, "read_write");
        complete(owner, "ac_code", startState()).andExpect(status().isConflict());
        assertThat(accounts.findById(tenant)).isEmpty();
        assertThat(stripe.deauthorized).containsExactly("acct_live");
    }

    @Test
    void aStripeRejectionIsReportedWithoutLeakingDetail() throws Exception {
        stripe.failExchange = new StripeConnectGateway.GatewayException("x", "invalid_grant", true, null);
        String msg = complete(owner, "ac_bad", startState()).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(msg).doesNotContain("ac_bad");
    }

    @Test
    void aFreeWorkspaceCantStartConnecting() throws Exception {
        plans.save(new TenantPlan(tenant, "FREE"));
        as(owner, post("/api/payments/connect")).andExpect(status().isPaymentRequired());
        JsonNode view = body(as(owner, get("/api/payments/account")).andExpect(status().isOk()));
        assertThat(view.path("planAllows").asBoolean()).isFalse();
    }

    @Test
    void disconnectRevokesAtStripeAndStopsPayments() throws Exception {
        complete(owner, "ac_code", startState()).andExpect(status().isOk());
        JsonNode view = body(as(owner, delete("/api/payments/account")).andExpect(status().isOk()));
        assertThat(view.path("ready").asBoolean()).isFalse();
        assertThat(view.path("account").path("status").asText()).isEqualTo(PaymentAccount.DISCONNECTED);
        assertThat(stripe.deauthorized).containsExactly(acct);
        as(owner, delete("/api/payments/account")).andExpect(status().isNotFound());
    }

    /** A second workspace, owned by {@link #owner}, that also connects {@link #acct}. */
    private String secondWorkspaceOnSameAccount() {
        String other = tenant + "b";
        Tenant t = identity.newTenant(other);
        t.setName(other);
        identity.saveTenant(t);
        identity.createTenantUserMembership(other, owner);
        Group g = identity.newGroup("owner:" + other);
        g.setName("Owners");
        g.setType("WORKFLOW");
        identity.saveGroup(g);
        identity.createMembership(owner, "owner:" + other);
        plans.save(new TenantPlan(other, "PRO"));
        PaymentAccount a = new PaymentAccount();
        a.setId(other);
        a.setStripeAccountId(acct);
        a.setStatus(PaymentAccount.CONNECTED);
        a.setLivemode(false);
        a.setChargesEnabled(true);
        a.setDetailsSubmitted(true);
        a.setLastSyncedAt(LocalDateTime.now());
        accounts.save(a);
        return other;
    }

    @Test
    void disconnectingASharedAccountLeavesTheGrantForTheOtherWorkspace() throws Exception {
        complete(owner, "ac_code", startState()).andExpect(status().isOk());
        String other = secondWorkspaceOnSameAccount();
        as(owner, delete("/api/payments/account")).andExpect(status().isOk());
        assertThat(stripe.deauthorized).isEmpty();
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.DISCONNECTED);
        assertThat(accounts.findById(other).orElseThrow().getStatus()).isEqualTo(PaymentAccount.CONNECTED);
    }

    @Test
    void aFailedRevokeKeepsTheAccountConnectedSoTheOwnerCanRetry() throws Exception {
        complete(owner, "ac_code", startState()).andExpect(status().isOk());
        stripe.failDeauthorize = new StripeConnectGateway.GatewayException("down", "network", false, null);
        as(owner, delete("/api/payments/account")).andExpect(status().isBadGateway());
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.CONNECTED);
        // "Already revoked" is the outcome we wanted.
        stripe.failDeauthorize = new StripeConnectGateway.GatewayException("gone", "invalid_client", true, null);
        as(owner, delete("/api/payments/account")).andExpect(status().isOk());
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.DISCONNECTED);
    }

    @Test
    void disconnectCancelsOpenChargesFirst_andWaitsOnAProcessingOne() throws Exception {
        complete(owner, "ac_code", startState()).andExpect(status().isOk());
        FormPayment open = openCharge("REQUIRES_PAYMENT");
        FormPayment processing = openCharge("PROCESSING");
        as(owner, delete("/api/payments/account")).andExpect(status().isConflict());
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.CONNECTED);
        assertThat(stripe.deauthorized).isEmpty();

        stripe.setStatus(processing.getIntentId(), "succeeded");
        payments.findById(processing.getId()).ifPresent(p -> {
            p.setStatus(FormPayment.SUCCEEDED);
            payments.save(p);
        });
        as(owner, delete("/api/payments/account")).andExpect(status().isOk());
        assertThat(stripe.cancelled).contains(open.getIntentId());
        assertThat(payments.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.CANCELED);
        assertThat(stripe.deauthorized).containsExactly(acct);
    }

    @Test
    void aDisconnectThatCantCloseAChargeIsRefused_andTheAccountKeepsWorking() throws Exception {
        complete(owner, "ac_code", startState()).andExpect(status().isOk());
        FormPayment open = openCharge("REQUIRES_PAYMENT");
        stripe.failCancel = new StripeConnectGateway.GatewayException("down", "api_error", false, 503, false, null);
        as(owner, delete("/api/payments/account")).andExpect(status().isBadGateway());
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.CONNECTED);
        assertThat(stripe.deauthorized).isEmpty();
        // The charge was taken out of the payer's hands, and the retry finishes the job.
        assertThat(payments.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.FAILED);
        stripe.failCancel = null;
        as(owner, delete("/api/payments/account")).andExpect(status().isOk());
        assertThat(payments.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.CANCELED);
        assertThat(stripe.deauthorized).containsExactly(acct);
    }

    @Test
    void anInterruptedDisconnectCanBeRetried() throws Exception {
        complete(owner, "ac_code", startState()).andExpect(status().isOk());
        PaymentAccount a = accounts.findById(tenant).orElseThrow();
        a.setStatus(PaymentAccount.DISCONNECTING);
        accounts.save(a);
        as(owner, delete("/api/payments/account")).andExpect(status().isOk());
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.DISCONNECTED);
    }

    /** A charge row with a live fake intent (no submission behind it — release is a no-op). */
    private FormPayment openCharge(String status) {
        String pi = stripe.createPaymentIntent(new StripeConnectGateway.CreateIntent(acct, 500, "USD", null, Map.of(),
                "k-" + UUID.randomUUID())).id();
        if ("PROCESSING".equals(status)) stripe.setStatus(pi, "processing");
        FormPayment p = new FormPayment();
        p.setTenantId(tenant);
        p.setInstanceId("inst-" + UUID.randomUUID());
        p.setFormCode("F");
        p.setFormVersion(1);
        p.setDoor("EMBED");
        p.setFieldKey("pay");
        p.setStripeAccountId(acct);
        p.setAmountMinor(500);
        p.setCurrency("USD");
        p.setMode("fixed");
        p.setIntentId(pi);
        p.setStatus(status);
        return payments.save(p);
    }

    @Test
    void theOAuthReturnCompletesForTheWorkspaceThatStartedIt() throws Exception {
        String other = secondWorkspaceOnSameAccount();
        accounts.deleteById(other);
        // Start in the OTHER workspace…
        String url = body(mvc.perform(post("/api/payments/connect").header("Authorization", basic(owner))
                .header("X-Tenant-Id", other)).andExpect(status().isOk())).path("url").asText();
        String state = url.substring(url.indexOf("state=") + "state=".length());
        // …and come back with the first one selected.
        JsonNode view = body(complete(owner, "ac_code", state).andExpect(status().isOk()));
        assertThat(view.path("tenantId").asText()).isEqualTo(other);
        assertThat(accounts.findById(other).orElseThrow().getStatus()).isEqualTo(PaymentAccount.CONNECTED);
        assertThat(accounts.findById(tenant)).isEmpty();
    }

    @Test
    void theOAuthReturnNeedsTheStartingWorkspacesOwner() throws Exception {
        String state = startState();
        // A member (not an owner) of the starting workspace is refused, and the state survives for the owner.
        complete(member, "ac_code", state).andExpect(status().isForbidden());
        complete(owner, "ac_code", state).andExpect(status().isOk());
    }

    @Test
    void aRevokedAccountIsNoticedOnTheNextStatusRead() throws Exception {
        complete(owner, "ac_code", startState()).andExpect(status().isOk());
        FormPayment open = openCharge("REQUIRES_PAYMENT");
        accounts.findById(tenant).ifPresent(a -> {
            a.setLastSyncedAt(LocalDateTime.now().minusHours(1));
            accounts.save(a);
        });
        stripe.revoked.add(acct);
        JsonNode view = body(as(owner, get("/api/payments/account")).andExpect(status().isOk()));
        assertThat(view.path("account").path("status").asText()).isEqualTo(PaymentAccount.DISCONNECTED);
        assertThat(payments.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.UNRESOLVED);
    }

    @Test
    void refreshRereadsTheAccount() throws Exception {
        stripe.account = new StripeConnectGateway.AccountSnapshot("acct_fake", false, false, "Fake Co", "usd", "US");
        complete(owner, "ac_code", startState()).andExpect(status().isOk());
        assertThat(accounts.findById(tenant).orElseThrow().isChargesEnabled()).isFalse();
        stripe.account = new StripeConnectGateway.AccountSnapshot("acct_fake", true, true, "Fake Co", "usd", "US");
        JsonNode view = body(as(owner, post("/api/payments/account/refresh")).andExpect(status().isOk()));
        assertThat(view.path("ready").asBoolean()).isTrue();
    }

    @Test
    void aSubmissionsPaymentNeedsFormsReadAccess() throws Exception {
        as(outsider, get("/api/payments/submissions/nope")).andExpect(status().isForbidden());
        // A member without FORMS access can't read submission payments…
        as(member, get("/api/payments/submissions/nope")).andExpect(status().isForbidden());
        com.luke.engine.capability.capability.CapabilitySubscription sub =
                new com.luke.engine.capability.capability.CapabilitySubscription(tenant, "FORMS");
        sub.setStatus("ACTIVE");
        subscriptions.save(sub);
        as(member, get("/api/payments/submissions/nope")).andExpect(status().isForbidden());
        // …one with it (or an owner) can.
        com.luke.engine.capability.access.CapabilityGrant g =
                new com.luke.engine.capability.access.CapabilityGrant(tenant, member, "FORMS");
        g.setLevel("read");
        grants.save(g);
        as(member, get("/api/payments/submissions/nope")).andExpect(status().isNotFound());
        as(owner, get("/api/payments/submissions/nope")).andExpect(status().isNotFound());

        FormPayment p = openCharge("REQUIRES_PAYMENT");
        String res = as(member, get("/api/payments/submissions/" + p.getInstanceId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(res).doesNotContain("_secret_");
    }
}
