package com.luke.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.capability.access.CapabilityGrant;
import com.luke.engine.capability.access.CapabilityGrantRepository;
import com.luke.engine.capability.capability.CapabilitySubscription;
import com.luke.engine.capability.capability.CapabilitySubscriptionRepository;
import com.luke.engine.capability.secrets.SecretStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.finos.fluxnova.bpm.engine.identity.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Bring-your-own-key end to end: who may connect a provider, what a response may contain, and what
 * the agent proxy actually puts on the wire.
 *
 * <p>The fleet is a local HTTP server here, so the assertions are about the real outbound request —
 * which tenant it claims, which key it carries, and what of the caller's own request survives the
 * hop (nothing).
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:aiprovider;DB_CLOSE_DELAY=-1",
        "luke.forms.outbox-enabled=false",
        "luke.payments.reconcile-enabled=false",
        "luke.ai.service-key=svc-secret"
})
@AutoConfigureMockMvc
class AiProviderControllerTest {

    /* ── the fleet, standing in for luke-agents ───────────────────────────── */

    record Seen(String path, Map<String, String> headers, String body) {}

    private static HttpServer fleet;
    private static final List<Seen> SEEN = new ArrayList<>();
    private static volatile int fleetStatus = 200;
    private static volatile String fleetBody = "{\"reply\":\"ok\"}";
    private static volatile String fleetCredentialSignal = null;

    @BeforeAll
    static void startFleet() throws IOException {
        fleet = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fleet.createContext("/", exchange -> {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            SEEN.add(new Seen(exchange.getRequestURI().getPath(), headers, body));
            byte[] out = fleetBody.getBytes(StandardCharsets.UTF_8);
            if (fleetCredentialSignal != null) {
                exchange.getResponseHeaders().add("X-AI-Credential", fleetCredentialSignal);
            }
            exchange.sendResponseHeaders(fleetStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        fleet.start();
    }

    @AfterAll
    static void stopFleet() {
        fleet.stop(0);
    }

    @DynamicPropertySource
    static void fleetUrl(DynamicPropertyRegistry registry) {
        registry.add("luke.ai.agents-url", () -> "http://127.0.0.1:" + fleet.getAddress().getPort());
    }

    /* ── the provider, stubbed: a unit test must not call api.groq.com ────── */

    static class FakeProbe extends AiProviderProbe {
        volatile Outcome outcome = Outcome.OK;
        volatile List<String> models = List.of();
        final List<String> keysSeen = new ArrayList<>();

        @Override
        public Result verify(AiProviderCatalog.Provider provider, String apiKey) {
            keysSeen.add(apiKey);
            return new Result(outcome, models, outcome == Outcome.OK ? null : "provider says no");
        }
    }

    @TestConfiguration
    static class FakeProbeConfig {
        @Bean
        @Primary
        FakeProbe fakeProbe() {
            return new FakeProbe();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "gsk_workspace_secret_key_abcd";

    @Autowired MockMvc mvc;
    @Autowired IdentityService identity;
    @Autowired FakeProbe probe;
    @Autowired SecretStore secrets;
    @Autowired AiProviderRepository providers;
    @Autowired CapabilitySubscriptionRepository subscriptions;
    @Autowired CapabilityGrantRepository grants;

    private String tenant;
    private String owner;
    private String member;
    private String outsider;

    @BeforeEach
    void setUp() {
        SEEN.clear();
        fleetStatus = 200;
        fleetBody = "{\"reply\":\"ok\"}";
        fleetCredentialSignal = null;
        probe.outcome = AiProviderProbe.Outcome.OK;
        probe.models = List.of();
        probe.keysSeen.clear();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenant = "tai" + suffix;
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

        CapabilitySubscription sub = new CapabilitySubscription(tenant, "FORMS");
        sub.setStatus("ACTIVE");
        subscriptions.save(sub);
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

    private ResultActions connect(String user, String provider, String key, String model) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", provider);
        payload.put("apiKey", key);
        if (model != null) payload.put("model", model);
        return as(user, put("/api/ai/provider").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(payload)));
    }

    private ResultActions chat(String user) throws Exception {
        return as(user, post("/api/ai/agents/form/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"build me a form\"}"));
    }

    /* ── who may do what ──────────────────────────────────────────────────── */

    @Test
    void anOutsiderSeesNothingAndChangesNothing() throws Exception {
        as(outsider, get("/api/ai/provider")).andExpect(status().isForbidden());
        connect(outsider, "groq", KEY, null).andExpect(status().isForbidden());
        as(outsider, delete("/api/ai/provider")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/provider").header("X-Tenant-Id", tenant)).andExpect(status().isUnauthorized());
    }

    @Test
    void anyMemberMayReadTheStatusButOnlyTheOwnerMayConnect() throws Exception {
        // Every AI panel needs to know whether the assistant is usable before offering itself.
        JsonNode view = body(as(member, get("/api/ai/provider")).andExpect(status().isOk()));
        assertThat(view.path("connected").asBoolean()).isFalse();
        assertThat(view.path("canManage").asBoolean()).isFalse();

        connect(member, "groq", KEY, null).andExpect(status().isForbidden());
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        assertThat(body(as(owner, get("/api/ai/provider"))).path("canManage").asBoolean()).isTrue();
    }

    /* ── connecting ───────────────────────────────────────────────────────── */

    @Test
    void theKeyGoesToTheEncryptedSecretStoreAndNeverIntoTheRow() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());

        assertThat(secrets.get(tenant, AiProvider.SECRET_NAME)).contains(KEY);
        AiProvider row = providers.findById(tenant).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AiProvider.CONNECTED);
        assertThat(row.getKeyLast4()).isEqualTo("abcd");
        assertThat(row.getKeyFingerprint()).hasSize(64).doesNotContain(KEY);
        // Nothing on the row may be the key itself.
        assertThat(String.valueOf(row.getModel()) + row.getProvider() + row.getKeyLast4() + row.getKeyFingerprint())
                .doesNotContain(KEY);
    }

    @Test
    void noResponseEverContainsTheKey() throws Exception {
        String connectBody = connect(owner, "groq", KEY, null).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        String viewBody = as(owner, get("/api/ai/provider")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        String configBody = as(owner, get("/api/ai/config")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String b : new String[] {connectBody, viewBody, configBody}) {
            assertThat(b).doesNotContain(KEY);
        }
        assertThat(connectBody).contains("abcd");  // the last four is the only fragment
    }

    @Test
    void aKeyForTheWrongProviderIsCaughtBeforeAnyNetworkCall() throws Exception {
        connect(owner, "openai", "sk-ant-api03-wrong-one", null).andExpect(status().isBadRequest());
        assertThat(probe.keysSeen).as("a paste error must not cost a round trip").isEmpty();
        assertThat(providers.findById(tenant)).isEmpty();
    }

    @Test
    void aKeyTheProviderRefusesIsNeverStored() throws Exception {
        probe.outcome = AiProviderProbe.Outcome.INVALID;
        connect(owner, "groq", KEY, null).andExpect(status().isBadRequest());
        assertThat(providers.findById(tenant)).isEmpty();
        assertThat(secrets.get(tenant, AiProvider.SECRET_NAME)).isEmpty();
    }

    @Test
    void aProviderWeCannotReachDoesNotLeaveTheWorkspaceLookingConnected() throws Exception {
        // Storing an unverified key would show "connected" while every turn fails.
        probe.outcome = AiProviderProbe.Outcome.UNREACHABLE;
        connect(owner, "groq", KEY, null).andExpect(status().isServiceUnavailable());
        assertThat(providers.findById(tenant)).isEmpty();
        assertThat(secrets.get(tenant, AiProvider.SECRET_NAME)).isEmpty();
    }

    @Test
    void aModelTheAccountCannotUseIsRefused() throws Exception {
        probe.models = List.of("openai/gpt-oss-120b", "llama-3.3-70b-versatile");
        connect(owner, "groq", KEY, "some-model-they-dont-have").andExpect(status().isBadRequest());
        connect(owner, "groq", KEY, "llama-3.3-70b-versatile").andExpect(status().isOk());
        assertThat(providers.findById(tenant).orElseThrow().getModel()).isEqualTo("llama-3.3-70b-versatile");
    }

    @Test
    void noModelMeansTheProviderDefaultRatherThanAFrozenOne() throws Exception {
        connect(owner, "groq", KEY, "   ").andExpect(status().isOk());
        // Stored as null so the default tracks the catalog instead of whatever it was today.
        assertThat(providers.findById(tenant).orElseThrow().getModel()).isNull();
        assertThat(body(as(owner, get("/api/ai/provider"))).path("effectiveModel").asText())
                .isEqualTo(AiProviderCatalog.GROQ.defaultModel());
    }

    /* ── the proxy: what actually goes on the wire ────────────────────────── */

    @Test
    void aWorkspaceWithNoProviderIsAskedToConnectOne() throws Exception {
        chat(owner).andExpect(status().isPaymentRequired());
        assertThat(SEEN).as("nothing should reach the fleet").isEmpty();
    }

    @Test
    void theProxyAttachesTheWorkspacesOwnKeyAndTheVerifiedTenant() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        chat(owner).andExpect(status().isOk());

        assertThat(SEEN).hasSize(1);
        Seen call = SEEN.get(0);
        assertThat(call.path()).isEqualTo("/agents/form/chat");
        assertThat(call.headers()).containsEntry("x-ai-provider", "groq");
        assertThat(call.headers()).containsEntry("x-ai-key", KEY);
        assertThat(call.headers()).containsEntry("x-tenant-id", tenant);
        assertThat(call.headers()).containsEntry("x-agents-key", "svc-secret");
        assertThat(call.body()).contains("build me a form");
    }

    @Test
    void nothingOfTheCallersOwnRequestSurvivesTheHop() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        SEEN.clear();
        as(owner, post("/api/ai/agents/form/chat").contentType(MediaType.APPLICATION_JSON)
                .header("X-Sneaky", "value")
                .content("{\"message\":\"hi\"}")).andExpect(status().isOk());

        Map<String, String> headers = SEEN.get(0).headers();
        // The caller's credential must never be handed to another service...
        assertThat(headers).doesNotContainKey("authorization");
        // ...and nothing else of theirs rides along either.
        assertThat(headers).doesNotContainKey("x-sneaky");
    }

    @Test
    void theTenantTheFleetSeesIsTheOneWeVerifiedNotTheOneClaimed() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        SEEN.clear();
        // Claim a workspace the caller does not belong to: refused before any key is resolved.
        mvc.perform(post("/api/ai/agents/form/chat")
                        .header("Authorization", basic(owner))
                        .header("X-Tenant-Id", "some-other-workspace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
        assertThat(SEEN).isEmpty();
    }

    @Test
    void theAgentAndOperationCannotSteerTheOutboundRequest() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        SEEN.clear();
        // Unknown agent: refused by name, not defaulted.
        as(owner, post("/api/ai/agents/nope/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        // Anything outside the strict segment pattern never reaches a URL.
        as(owner, post("/api/ai/agents/form/..%2f..%2fhealth").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        as(owner, post("/api/ai/agents/FORM/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        assertThat(SEEN).isEmpty();
    }

    @Test
    void usingTheAssistantNeedsWriteAccessToThatCapability() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        SEEN.clear();
        // A member with no FORMS grant can't spend the workspace's money through the builder.
        chat(member).andExpect(status().isForbidden());

        CapabilityGrant readOnly = new CapabilityGrant(tenant, member, "FORMS");
        readOnly.setLevel("read");
        grants.save(readOnly);
        chat(member).andExpect(status().isForbidden());   // read is not enough: a turn authors content

        CapabilityGrant contributor = grants.findAll().stream()
                .filter(g -> member.equals(g.getUserId())).findFirst().orElseThrow();
        contributor.setLevel("contributor");
        grants.save(contributor);
        chat(member).andExpect(status().isOk());
        assertThat(SEEN).hasSize(1);
    }

    /* ── what the fleet tells us back ─────────────────────────────────────── */

    @Test
    void aRejectedKeyMarksTheWorkspaceAndStopsFurtherTurns() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        fleetStatus = 402;
        fleetCredentialSignal = "invalid";
        fleetBody = "{\"detail\":\"Your AI provider rejected this workspace's API key.\"}";

        chat(owner).andExpect(status().isPaymentRequired());
        assertThat(providers.findById(tenant).orElseThrow().getStatus()).isEqualTo(AiProvider.INVALID);

        // The next turn is refused here, without troubling the provider again.
        SEEN.clear();
        chat(owner).andExpect(status().isPaymentRequired());
        assertThat(SEEN).isEmpty();
    }

    @Test
    void aFleetOutageNeverMarksTheWorkspacesKeyBad() throws Exception {
        // The worst failure mode this feature has: disconnecting a working workspace because
        // something on OUR side had a bad minute.
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        fleetStatus = 503;
        fleetBody = "{\"detail\":\"temporarily unavailable\"}";
        chat(owner).andExpect(status().isServiceUnavailable());
        assertThat(providers.findById(tenant).orElseThrow().getStatus()).isEqualTo(AiProvider.CONNECTED);

        fleetStatus = 429;
        chat(owner).andExpect(status().isTooManyRequests());
        assertThat(providers.findById(tenant).orElseThrow().getStatus()).isEqualTo(AiProvider.CONNECTED);
    }

    @Test
    void reconnectingClearsAnInvalidMark() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        fleetStatus = 402;
        fleetCredentialSignal = "invalid";
        chat(owner).andExpect(status().isPaymentRequired());

        connect(owner, "groq", "gsk_a_fresh_working_key_wxyz", null).andExpect(status().isOk());
        AiProvider row = providers.findById(tenant).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AiProvider.CONNECTED);
        assertThat(row.getLastError()).isNull();
        assertThat(row.getKeyLast4()).isEqualTo("wxyz");
        assertThat(secrets.get(tenant, AiProvider.SECRET_NAME)).contains("gsk_a_fresh_working_key_wxyz");
    }

    /* ── disconnecting ────────────────────────────────────────────────────── */

    @Test
    void disconnectingDestroysTheKeyButKeepsTheAuditTrail() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());
        as(owner, delete("/api/ai/provider")).andExpect(status().isOk());

        assertThat(secrets.get(tenant, AiProvider.SECRET_NAME)).isEmpty();
        AiProvider row = providers.findById(tenant).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AiProvider.DISCONNECTED);
        assertThat(row.getKeyFingerprint()).isNull();
        assertThat(row.getConnectedBy()).isEqualTo(owner);   // who did it survives
        assertThat(row.getDisconnectedAt()).isNotNull();

        chat(owner).andExpect(status().isPaymentRequired());
    }

    @Test
    void verifyRecordsWhatTheProviderSaysWithoutGuessing() throws Exception {
        connect(owner, "groq", KEY, null).andExpect(status().isOk());

        probe.outcome = AiProviderProbe.Outcome.INVALID;
        as(owner, post("/api/ai/provider/verify")).andExpect(status().isOk());
        assertThat(providers.findById(tenant).orElseThrow().getStatus()).isEqualTo(AiProvider.INVALID);

        probe.outcome = AiProviderProbe.Outcome.OK;
        as(owner, post("/api/ai/provider/verify")).andExpect(status().isOk());
        assertThat(providers.findById(tenant).orElseThrow().getStatus()).isEqualTo(AiProvider.CONNECTED);

        // UNREACHABLE is our problem, not theirs: the row must be left exactly as it was.
        probe.outcome = AiProviderProbe.Outcome.UNREACHABLE;
        as(owner, post("/api/ai/provider/verify")).andExpect(status().isOk());
        assertThat(providers.findById(tenant).orElseThrow().getStatus()).isEqualTo(AiProvider.CONNECTED);
    }
}
