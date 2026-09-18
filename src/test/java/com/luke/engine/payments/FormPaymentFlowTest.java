package com.luke.engine.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.branding.TenantPlan;
import com.luke.engine.branding.TenantPlanRepository;
import com.luke.engine.capability.form.EmbedTokens;
import com.luke.engine.capability.form.FormDefinition;
import com.luke.engine.capability.form.FormDefinitionRepository;
import com.luke.engine.capability.form.FormEventOutboxRepository;
import com.luke.engine.capability.form.FormInstance;
import com.luke.engine.capability.form.FormInstanceRepository;
import com.luke.engine.capability.form.FormInstanceStates;
import com.luke.engine.capability.form.FormSubmissionOutboxRepository;
import com.luke.engine.capability.form.FormSubmissionService;
import com.luke.engine.capability.form.FormVersion;
import com.luke.engine.capability.form.FormVersionRepository;
import com.luke.engine.capability.form.RecipientAccessTokens;
import com.luke.engine.capability.form.SubmissionSource;
import com.stripe.net.Webhook;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Form payments end to end, against the real Spring context (H2) with Stripe replaced by
 * {@link FakeStripeGateway}. The properties below switch payments ON with test-mode keys; nothing
 * leaves the process.
 *
 * <p>The invariants under test are the money ones: the amount is the server's, a submission is never
 * released to its process before Stripe reports the charge succeeded, only Stripe-reported states move
 * a charge, abandoned charges are cancelled at Stripe before a submission is released, and nothing is
 * charged twice.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:paymentflow;DB_CLOSE_DELAY=-1",
        "luke.forms.outbox-enabled=false",
        "luke.embed.captcha.enabled=false",
        "luke.payments.stripe.secret-key=sk_test_platform",
        "luke.payments.stripe.publishable-key=pk_test_platform",
        "luke.payments.stripe.connect-client-id=ca_test",
        "luke.payments.stripe.connect-webhook-secret=whsec_test_connect",
        "luke.payments.reconcile-enabled=false",
        "luke.payments.pending-ttl-minutes=5",
        // Every test submits from 127.0.0.1; the per-IP cap is not what's under test here.
        "luke.embed.submit.max-per-ip-per-min=10000",
        "luke.embed.submit.max-per-token-per-min=10000"
})
@AutoConfigureMockMvc
class FormPaymentFlowTest {

    @TestConfiguration
    static class FakeStripeConfig {
        @Bean
        @Primary
        FakeStripeGateway fakeStripeGateway() {
            return new FakeStripeGateway();
        }
    }

    private static final String SCHEMA = """
            {"root":["name","qty","pay"],"entities":{
              "name":{"id":"name","type":"textField","attributes":{"key":"name","label":"Name","required":true}},
              "qty":{"id":"qty","type":"number","attributes":{"key":"qty","label":"Tickets","required":true}},
              "pay":{"id":"pay","type":"payment","attributes":{"key":"pay","label":"Payment","amountMode":"perUnit",
                "amountMinor":1500,"quantityFrom":"qty","maxQuantity":10,"currency":"USD","chargeDescription":"Ticket"}}
            }}""";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired FakeStripeGateway stripe;
    @Autowired FormDefinitionRepository forms;
    @Autowired FormVersionRepository versions;
    @Autowired FormInstanceRepository instances;
    @Autowired FormSubmissionOutboxRepository outbox;
    @Autowired FormEventOutboxRepository events;
    @Autowired FormPaymentRepository payments;
    @Autowired PaymentAccountRepository accounts;
    @Autowired TenantPlanRepository plans;
    @Autowired EmbedTokens embedTokens;
    @Autowired RecipientAccessTokens recipientTokens;
    @Autowired FormSubmissionService submissions;
    @Autowired FormPaymentService paymentService;
    @Autowired StripeConnectWebhookController webhook;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired com.luke.engine.capability.form.FormInstanceController formInstanceController;
    @Autowired com.luke.engine.audit.AuditEventRepository auditEvents;
    @Autowired com.luke.engine.capability.access.CapabilityAdminController admin;
    @Autowired PaymentWebhookEventRepository webhookEvents;

    private String tenant;
    private String token;

    @BeforeEach
    void setUp() {
        stripe.reset();
        tenant = "t-pay-" + UUID.randomUUID().toString().substring(0, 8);
        plans.save(new TenantPlan(tenant, "PRO"));
        connectAccount(tenant, true);
        FormDefinition form = form(tenant, "TICKETS", FormDefinition.KIND_INBOUND);
        token = embedTokens.sign(tenant, form.getCode(), form.getEmbedKeyVersion());
    }

    /* ── fixtures ─────────────────────────────────────────────────────────── */

    private void connectAccount(String tenantId, boolean chargesEnabled) {
        PaymentAccount a = new PaymentAccount();
        a.setId(tenantId);
        a.setStripeAccountId("acct_" + tenantId.replace("-", ""));
        a.setStatus(PaymentAccount.CONNECTED);
        a.setLivemode(false);
        a.setChargesEnabled(chargesEnabled);
        a.setDetailsSubmitted(true);
        a.setLastSyncedAt(LocalDateTime.now());
        accounts.save(a);
    }

    private FormDefinition form(String tenantId, String code, String kind) {
        FormDefinition f = new FormDefinition();
        f.setTenantId(tenantId);
        f.setCode(code);
        f.setName("Tickets");
        f.setKind(kind);
        f.setStatus("PUBLISHED");
        f.setSubmissionHandling("PROCESS");
        f.setPublishedVersion(1);
        f.setDraftSchema(SCHEMA);
        f = forms.save(f);
        FormVersion v = new FormVersion(f.getId(), 1, SCHEMA, "author");
        v.setSignedOffAt(LocalDateTime.now());
        versions.save(v);
        return f;
    }

    private JsonNode submitEmbed(Map<String, Object> data, int expectStatus) throws Exception {
        MvcResult r = mvc.perform(post("/api/public/embed/" + token + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("data", data, "consentAgreed", true))))
                .andExpect(status().is(expectStatus))
                .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    private JsonNode syncEmbed(String instanceId) throws Exception {
        MvcResult r = mvc.perform(post("/api/public/embed/" + token + "/payments/" + instanceId + "/sync"))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private FormInstance instance(String id) {
        return instances.findById(id).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payValue(String instanceId) {
        return (Map<String, Object>) instance(instanceId).getData().get("pay");
    }

    private long submittedEvents(String instanceId) {
        return events.findAll().stream()
                .filter(e -> instanceId.equals(e.getInstanceId()) && "submitted".equals(e.getEventType()))
                .count();
    }

    private void ageAllPayments() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> em
                .createNativeQuery("update luke_form_payment set updated_at = :t")
                .setParameter("t", LocalDateTime.now().minusHours(3))
                .executeUpdate());
    }

    /* ── embed door ───────────────────────────────────────────────────────── */

    @Test
    void renderCarriesOnlyPublicPaymentKeys() throws Exception {
        MvcResult r = mvc.perform(get("/api/public/embed/" + token)).andExpect(status().isOk()).andReturn();
        JsonNode payment = JSON.readTree(r.getResponse().getContentAsString()).path("payment");
        assertThat(payment.path("available").asBoolean()).isTrue();
        assertThat(payment.path("publishableKey").asText()).isEqualTo("pk_test_platform");
        assertThat(payment.path("accountId").asText()).startsWith("acct_");
        assertThat(payment.path("scriptUrl").asText()).isEqualTo("https://js.stripe.com/v3/");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("sk_test").doesNotContain("whsec");
    }

    @Test
    void paidSubmissionIsHeldUntilStripeConfirms_thenReleasedOnce() throws Exception {
        // The payer's browser claims it already paid one cent. It is ignored: the server prices 3 × 15.00.
        Map<String, Object> data = new HashMap<>(Map.of("name", "Ada", "qty", 3));
        data.put("pay", Map.of("status", "paid", "amountMinor", 1, "currency", "USD", "intentId", "pi_forged"));
        JsonNode res = submitEmbed(data, 200);

        assertThat(res.path("processStatus").asText()).isEqualTo("AWAITING_PAYMENT");
        String id = res.path("instanceId").asText();
        JsonNode payment = res.path("payment");
        assertThat(payment.path("amountMinor").asLong()).isEqualTo(4500);
        assertThat(payment.path("currency").asText()).isEqualTo("USD");
        assertThat(payment.path("clientSecret").asText()).endsWith("_secret_x");

        // Exactly what would reach Stripe: a card-only direct charge on the tenant's account.
        assertThat(stripe.created).hasSize(1);
        StripeConnectGateway.CreateIntent sent = stripe.created.get(0);
        assertThat(sent.accountId()).isEqualTo(accounts.findById(tenant).orElseThrow().getStripeAccountId());
        assertThat(sent.amountMinor()).isEqualTo(4500);
        assertThat(sent.currency()).isEqualTo("USD");
        assertThat(sent.description()).isEqualTo("Ticket");
        assertThat(sent.metadata()).containsEntry("lukeflow_submission", id).containsEntry("lukeflow_quantity", "3");
        assertThat(sent.idempotencyKey()).startsWith("lukeflow-form-payment-");

        // Held: not submitted, not queued, no workflow event.
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        assertThat(payValue(id)).containsEntry("status", "pending").containsEntry("amountMinor", 4500);
        assertThat(outbox.findByBusinessKey(id)).isEmpty();
        assertThat(submittedEvents(id)).isZero();

        // The payer's page asks us to check before Stripe has settled: still held.
        assertThat(syncEmbed(id).path("status").asText()).isEqualTo("requires_payment");
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);

        // Stripe reports success → released exactly once.
        stripe.setStatus(stripe.onlyIntentId(), "succeeded");
        JsonNode synced = syncEmbed(id);
        assertThat(synced.path("status").asText()).isEqualTo("succeeded");
        assertThat(synced.path("processStatus").asText()).isEqualTo("QUEUED");
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.SUBMITTED);
        assertThat(payValue(id)).containsEntry("status", "paid").containsEntry("intentId", stripe.onlyIntentId());
        assertThat(outbox.findByBusinessKey(id)).isPresent();
        assertThat(submittedEvents(id)).isEqualTo(1);

        syncEmbed(id);
        assertThat(submittedEvents(id)).isEqualTo(1);
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.SUCCEEDED);
    }

    @Test
    void aDeclineKeepsTheChargePayable() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        stripe.setStatus(stripe.onlyIntentId(), "requires_payment_method", "card_declined", "Your card was declined.");
        JsonNode synced = syncEmbed(id);
        assertThat(synced.path("status").asText()).isEqualTo("requires_payment");
        assertThat(synced.path("message").asText()).contains("didn't go through");
        FormPayment p = payments.findByInstanceId(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(FormPayment.REQUIRES_PAYMENT);
        assertThat(p.getLastErrorCode()).isEqualTo("card_declined");
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
    }

    @Test
    void payerFixableAmountsAreRefusedWithAMessageAndNothingIsSaved() throws Exception {
        long before = instances.count();
        JsonNode res = submitEmbed(Map.of("name", "Ada", "qty", 11), 400);
        assertThat(res.toString()).contains("That quantity isn't available.");
        assertThat(instances.count()).isEqualTo(before);
        assertThat(stripe.created).isEmpty();
    }

    @Test
    void aTenantThatCantTakePaymentsIsRefusedAndRendersUnavailable() throws Exception {
        accounts.deleteById(tenant);
        MvcResult r = mvc.perform(get("/api/public/embed/" + token)).andExpect(status().isOk()).andReturn();
        JsonNode payment = JSON.readTree(r.getResponse().getContentAsString()).path("payment");
        assertThat(payment.path("available").asBoolean()).isFalse();
        assertThat(payment.path("publishableKey").isNull()).isTrue();
        long before = instances.count();
        submitEmbed(Map.of("name", "Ada", "qty", 1), 409);
        assertThat(instances.count()).isEqualTo(before);
    }

    @Test
    void aFreePlanTenantIsRefusedEvenWithAConnectedAccount() throws Exception {
        plans.save(new TenantPlan(tenant, "FREE"));
        submitEmbed(Map.of("name", "Ada", "qty", 1), 409);
        assertThat(stripe.created).isEmpty();
    }

    @Test
    void anAccountThatCantChargeYetIsRefused() throws Exception {
        connectAccount(tenant, false);
        submitEmbed(Map.of("name", "Ada", "qty", 1), 409);
    }

    @Test
    void theInAppDoorCannotSubmitAPaidForm() {
        FormInstance inst = new FormInstance();
        inst.setTenantId(tenant);
        inst.setToken("tok-" + UUID.randomUUID());
        inst.setDefinitionCode("TICKETS");
        inst.setVersion(1);
        inst.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(inst);
        SubmissionSource app = new SubmissionSource("1.1.1.1", "ua", SubmissionSource.VIA_APP, true);
        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> submissions.submit(inst, Map.of("name", "Ada", "qty", 1), null, app));
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
        // A null-source internal caller is refused too.
        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> submissions.submit(inst, Map.of("name", "Ada", "qty", 1)));
    }

    private long audits(String action, String tenantId) {
        return auditEvents.findByTenantIdAndActionOrderByCreatedAtDesc(tenantId, action,
                org.springframework.data.domain.PageRequest.of(0, 100)).getTotalElements();
    }

    @Test
    void aStripeOutageAtCreationKeepsTheChargeRetryable() throws Exception {
        stripe.failCreate = new StripeConnectGateway.GatewayException("down", "api_error", false, null);
        JsonNode res = submitEmbed(Map.of("name", "Ada", "qty", 1), 200);
        String id = res.path("instanceId").asText();
        assertThat(res.path("processStatus").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(res.path("payment").isNull()).isTrue();
        assertThat(res.path("paymentError").asText()).contains("try again");
        assertThat(res.path("paymentRetryable").asBoolean()).isTrue();
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.CREATING);
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        assertThat(outbox.findByBusinessKey(id)).isEmpty();

        // Stripe is back: the page retries and gets the charge (same idempotency key).
        stripe.failCreate = null;
        MvcResult r = mvc.perform(post("/api/public/embed/" + token + "/payments/" + id)).andExpect(status().isOk()).andReturn();
        JsonNode start = JSON.readTree(r.getResponse().getContentAsString());
        assertThat(start.path("clientSecret").asText()).endsWith("_secret_x");
        assertThat(stripe.created).hasSize(2);
        assertThat(stripe.created.get(0).idempotencyKey()).isEqualTo(stripe.created.get(1).idempotencyKey());
        assertThat(stripe.intents).hasSize(1);
    }

    @Test
    void theEmbedRetryEndpointIsScopedToAnAwaitingEmbedSubmission() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        stripe.setStatus(stripe.onlyIntentId(), "succeeded");
        syncEmbed(id);
        mvc.perform(post("/api/public/embed/" + token + "/payments/" + id)).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/embed/" + token + "/payments/nope")).andExpect(status().isNotFound());
    }

    @Test
    void aStripeRejectionAtCreationIsAPayerError() throws Exception {
        stripe.failCreate = new StripeConnectGateway.GatewayException("too small", "amount_too_small", true, null);
        JsonNode res = submitEmbed(Map.of("name", "Ada", "qty", 1), 200);
        String id = res.path("instanceId").asText();
        assertThat(res.path("paymentRetryable").asBoolean()).isFalse();
        assertThat(res.path("paymentError").asText()).contains("couldn't be started");
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.FAILED);
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.CANCELLED);
    }

    @Test
    void anIntentWhoseAmountIsNotOursIsNeverHandedOut() throws Exception {
        stripe.createAmountOverride = 1L;
        JsonNode res = submitEmbed(Map.of("name", "Ada", "qty", 1), 200);
        assertThat(res.toString()).doesNotContain("_secret_");
        assertThat(res.path("payment").isNull()).isTrue();
        assertThat(stripe.cancelled).hasSize(1);
        FormPayment p = payments.findByInstanceId(res.path("instanceId").asText()).orElseThrow();
        // The cancel is recorded, so the row no longer blocks a new attempt.
        assertThat(p.getStatus()).isEqualTo(FormPayment.CANCELED);
        assertThat(p.getIntentId()).isEqualTo(stripe.onlyIntentId());
        assertThat(FormPaymentService.replaceable(p)).isTrue();
    }

    @Test
    void anIntentThatSucceededAtADifferentAmountIsEscalated_notSettled() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        String pi = stripe.onlyIntentId();
        stripe.setAmount(pi, 1);
        stripe.setStatus(pi, "succeeded");
        syncEmbed(id);
        FormPayment p = payments.findByInstanceId(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(FormPayment.UNRESOLVED);
        assertThat(p.getLastErrorCode()).isEqualTo("intent_mismatch");
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.CANCELLED);
        assertThat(outbox.findByBusinessKey(id)).isEmpty();
        assertThat(audits("payments.unresolved", tenant)).isEqualTo(1);
    }

    @Test
    void aChangedIntentIsNeverHandedToAReturningPayer() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        String pi = stripe.onlyIntentId();
        stripe.setAmount(pi, 1);
        JsonNode resumed = respond("POST", base + "/payment", access, null, 200);
        assertThat(resumed.path("clientSecret").isNull()).isTrue();
        // It can never settle here, so it is ended at once and the recipient can submit again.
        assertThat(resumed.path("status").asText()).isEqualTo("canceled");
        assertThat(stripe.cancelled).containsExactly(pi);
        assertThat(payments.findByInstanceId(inst.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.CANCELED);
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
    }

    @Test
    void aCreateThatFinishesAfterTheAttemptEndedIsCancelled_notHandedOut() throws Exception {
        // The reconciler gives up on a CREATING row while its create call is still in flight.
        stripe.failCreate = new StripeConnectGateway.GatewayException("slow", "api_error", false, null);
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        ageAllPayments();
        paymentService.reconcileStale();
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.FAILED);
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.CANCELLED);

        // Now the in-flight create "returns": drive create() on the FAILED row via a fresh CREATING flip.
        stripe.failCreate = null;
        String pi = stripe.createPaymentIntent(new StripeConnectGateway.CreateIntent("acct_x", 1500, "USD", null, Map.of(),
                "lukeflow-form-payment-" + payments.findByInstanceId(id).orElseThrow().getId())).id();
        FormPaymentService.PaymentStart start = raceCreate(id);
        assertThat(start.clientSecret()).isNull();
        assertThat(stripe.cancelled).contains(pi);
        FormPayment p = payments.findByInstanceId(id).orElseThrow();
        assertThat(p.getIntentId()).isEqualTo(pi);
        assertThat(p.getStatus()).isEqualTo(FormPayment.CANCELED);
    }

    /**
     * Run create() for a row that is ALREADY failed, as the losing side of the race sees it: read the row
     * while CREATING, then let it fail, then finish.
     */
    private FormPaymentService.PaymentStart raceCreate(String instanceId) throws Exception {
        FormPayment row = payments.findByInstanceId(instanceId).orElseThrow();
        java.lang.reflect.Field status = FormPayment.class.getDeclaredField("status");
        status.setAccessible(true);
        status.set(row, FormPayment.CREATING); // the stale in-memory copy create() started from
        java.lang.reflect.Method create = FormPaymentService.class.getDeclaredMethod("create", FormPayment.class);
        create.setAccessible(true);
        return (FormPaymentService.PaymentStart) create.invoke(
                org.springframework.test.util.AopTestUtils.getTargetObject(paymentService), row);
    }

    @Test
    void aPaymentFormRenderedFromAnotherVersionIsRefused() throws Exception {
        mvc.perform(post("/api/public/embed/" + token + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("data", Map.of("name", "Ada", "qty", 1),
                                "consentAgreed", true, "version", 7))))
                .andExpect(status().isConflict());
        assertThat(stripe.created).isEmpty();
        mvc.perform(post("/api/public/embed/" + token + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("data", Map.of("name", "Ada", "qty", 1),
                                "consentAgreed", true, "version", 1))))
                .andExpect(status().isOk());
    }

    @Test
    void syncIsScopedToTheTokensOwnForm() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        String otherTenant = "t-other-" + UUID.randomUUID().toString().substring(0, 6);
        plans.save(new TenantPlan(otherTenant, "PRO"));
        FormDefinition other = form(otherTenant, "OTHER", FormDefinition.KIND_INBOUND);
        String otherToken = embedTokens.sign(otherTenant, other.getCode(), other.getEmbedKeyVersion());
        mvc.perform(post("/api/public/embed/" + otherToken + "/payments/" + id + "/sync")).andExpect(status().isNotFound());
    }

    @Test
    void staffCannotMoveAPaymentSubmissionByHand() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        com.luke.engine.capability.form.FormInstanceController ctl =
                org.springframework.test.util.AopTestUtils.getTargetObject(formInstanceController);
        for (String to : List.of(FormInstanceStates.SUBMITTED, FormInstanceStates.CANCELLED, FormInstanceStates.IN_PROGRESS)) {
            org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                    () -> ctl.setState(tenant, id, new com.luke.engine.capability.form.FormInstanceController.StateBody(to, null)), to);
        }
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        assertThat(outbox.findByBusinessKey(id)).isEmpty();

        // …and an OPEN instance of a payment form can't be marked submitted (or awaiting payment) either.
        FormInstance open = new FormInstance();
        open.setTenantId(tenant);
        open.setToken("tok-" + UUID.randomUUID());
        open.setDefinitionCode("TICKETS");
        open.setVersion(1);
        open.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(open);
        for (String to : List.of(FormInstanceStates.SUBMITTED, FormInstanceStates.AWAITING_PAYMENT)) {
            org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                    () -> ctl.setState(tenant, open.getId(), new com.luke.engine.capability.form.FormInstanceController.StateBody(to, null)), to);
        }
        assertThat(instance(open.getId()).getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
    }

    /* ── reconciliation ───────────────────────────────────────────────────── */

    @Test
    void anAbandonedChargeIsCancelledAtStripeBeforeTheSubmissionIsReleased() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 2), 200).path("instanceId").asText();
        ageAllPayments();
        assertThat(paymentService.reconcileStale()).isGreaterThanOrEqualTo(1);
        assertThat(stripe.cancelled).containsExactly(stripe.onlyIntentId());
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.CANCELED);
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.CANCELLED);
        assertThat(payValue(id)).containsEntry("status", "failed");
        assertThat(outbox.findByBusinessKey(id)).isEmpty();
    }

    @Test
    void aChargeThatSucceededBeforeTheCancelLandsIsSettledAsPaid() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 2), 200).path("instanceId").asText();
        String pi = stripe.onlyIntentId();
        ageAllPayments();
        // Between the reconciler's read (still payable) and its cancel, the payer's payment goes through,
        // so Stripe refuses the cancel.
        stripe.beforeCancel = () -> stripe.setStatus(pi, "succeeded");
        stripe.failCancel = new StripeConnectGateway.GatewayException("cannot cancel", "payment_intent_unexpected_state", true, null);
        paymentService.reconcileStale();
        assertThat(stripe.cancelled).containsExactly(pi);
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.SUCCEEDED);
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.SUBMITTED);
        assertThat(submittedEvents(id)).isEqualTo(1);
    }

    @Test
    void aProcessingChargeIsLeftOpen() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        stripe.setStatus(stripe.onlyIntentId(), "processing");
        syncEmbed(id);
        ageAllPayments();
        paymentService.reconcileStale();
        assertThat(stripe.cancelled).isEmpty();
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.PROCESSING);
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
    }

    @Test
    void aRowStripeCantReadIsRotated_andAccessLossEndsItAsUnresolved() throws Exception {
        String a = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        String b = submitEmbed(Map.of("name", "Bea", "qty", 1), 200).path("instanceId").asText();
        String piA = payments.findByInstanceId(a).orElseThrow().getIntentId();
        ageAllPayments();
        // A transient failure on A: it stays claimed (no payer can be handed its secret), its timestamp moves
        // so it goes behind the rest, and B is still handled.
        FormPayment before = payments.findByInstanceId(a).orElseThrow();
        stripe.transientRead.add(piA);
        paymentService.reconcileStale();
        FormPayment afterA = payments.findByInstanceId(a).orElseThrow();
        assertThat(afterA.getStatus()).isEqualTo(FormPayment.FAILED);
        assertThat(afterA.getLastErrorCode()).isEqualTo("abandoned");
        assertThat(afterA.getReconcileAttempts()).isEqualTo(1);
        assertThat(afterA.getUpdatedAt()).isAfter(before.getUpdatedAt());
        assertThat(instance(a).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        assertThat(payments.findByInstanceId(b).orElseThrow().getStatus()).isEqualTo(FormPayment.CANCELED);

        // …and the very next run tries it again (no waiting a whole TTL), finishing the cancel.
        paymentService.reconcileStale();
        assertThat(payments.findByInstanceId(a).orElseThrow().getReconcileAttempts()).isEqualTo(2);
        stripe.transientRead.clear();
        stripe.missing.clear();
        paymentService.reconcileStale();
        assertThat(payments.findByInstanceId(a).orElseThrow().getStatus()).isEqualTo(FormPayment.CANCELED);
        assertThat(instance(a).getState()).isEqualTo(FormInstanceStates.CANCELLED);
    }

    @Test
    void aMissingIntentEndsUnresolved_butAPlatformKeyProblemDoesNot() throws Exception {
        String a = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        String piA = payments.findByInstanceId(a).orElseThrow().getIntentId();
        ageAllPayments();
        // A 403 that isn't about the account (a restricted key missing a permission) is an operator problem.
        stripe.forbiddenRead.add(piA);
        paymentService.reconcileStale();
        assertThat(payments.findByInstanceId(a).orElseThrow().getStatus()).isEqualTo(FormPayment.FAILED);
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.CONNECTED);
        assertThat(audits("payments.unresolved", tenant)).isZero();
        stripe.forbiddenRead.clear();

        // The intent is gone for good (deleted test data) while the account is fine: A ends UNRESOLVED.
        stripe.missing.add(piA);
        ageAllPayments();
        paymentService.reconcileStale();
        FormPayment endA = payments.findByInstanceId(a).orElseThrow();
        assertThat(endA.getStatus()).isEqualTo(FormPayment.UNRESOLVED);
        assertThat(instance(a).getState()).isEqualTo(FormInstanceStates.CANCELLED);
        assertThat(audits("payments.unresolved", tenant)).isEqualTo(1);
    }

    @Test
    void theReconcilerStopsAtItsTimeBudget() throws Exception {
        submitEmbed(Map.of("name", "Ada", "qty", 1), 200);
        submitEmbed(Map.of("name", "Bea", "qty", 1), 200);
        ageAllPayments();
        stripe.beforeCancel = () -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        assertThat(paymentService.reconcileStale(java.time.Duration.ofMillis(1))).isEqualTo(1);
    }

    @Test
    void aPayerResumingWhileTheReconcilerCancelsIsNeverHandedTheSecret() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        String pi = stripe.onlyIntentId();
        ageAllPayments();
        JsonNode[] resumed = new JsonNode[1];
        // The payer comes back just as the reconciler asks Stripe about the (stale) charge.
        stripe.beforeRetrieve.put(pi, () -> {
            try {
                resumed[0] = respond("POST", base + "/payment", access, null, 200);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        paymentService.reconcileStale();
        assertThat(resumed[0].path("clientSecret").isNull()).isTrue();
        assertThat(stripe.cancelled).containsExactly(pi);
        assertThat(payments.findByInstanceId(inst.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.CANCELED);
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
    }

    @Test
    void aStaleChargeThatTurnsOutToBeProcessingIsLeftOpen_notCancelled() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        stripe.setStatus(stripe.onlyIntentId(), "processing"); // Stripe knows; we haven't synced yet
        ageAllPayments();
        paymentService.reconcileStale();
        assertThat(stripe.cancelled).isEmpty();
        FormPayment p = payments.findByInstanceId(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(FormPayment.PROCESSING);
        assertThat(p.getLastErrorCode()).isNull();
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
    }

    @Test
    void anUnresolvedChargeBlocksResubmission_untilStripeCanSayWhatHappened() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        String pi = stripe.onlyIntentId();
        String account = accounts.findById(tenant).orElseThrow().getStripeAccountId();
        // The tenant revokes Lukeflow in Stripe — after the payer paid, before anything settled.
        stripe.setStatus(pi, "succeeded");
        stripe.revoked.add(account);
        webhook.handle("account.application.deauthorized", account, JSON.readTree("{}"));
        assertThat(payments.findByInstanceId(inst.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.UNRESOLVED);
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);

        // They reconnect; the reopened form can't be resubmitted over the unresolved charge.
        stripe.revoked.clear();
        connectAccount(tenant, true);
        JsonNode refused = respond("POST", base + "/submit", access,
                Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 409);
        assertThat(refused.toString()).contains("contact the form owner");
        assertThat(stripe.created).hasSize(1);

        // Stripe can be read again: the reconciler records the payment, and a resubmit is not charged twice.
        paymentService.reconcileStale(); // not stale yet: nothing happens
        assertThat(payments.findByInstanceId(inst.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.UNRESOLVED);
        ageAllPayments();
        paymentService.reconcileStale();
        assertThat(payments.findByInstanceId(inst.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.SUCCEEDED);
        JsonNode again = respond("POST", base + "/submit", access,
                Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        assertThat(again.path("state").asText()).isEqualTo(FormInstanceStates.SUBMITTED);
        assertThat(stripe.created).hasSize(1);
    }

    @Test
    void aRevokedAccountFoundAtChargeTimeIsDisconnected() throws Exception {
        String account = accounts.findById(tenant).orElseThrow().getStripeAccountId();
        stripe.revoked.add(account);
        JsonNode res = submitEmbed(Map.of("name", "Ada", "qty", 1), 200);
        assertThat(res.path("paymentError").asText()).contains("can't take payments");
        assertThat(res.path("paymentRetryable").asBoolean()).isFalse();
        assertThat(payments.findByInstanceId(res.path("instanceId").asText()).orElseThrow().getStatus()).isEqualTo(FormPayment.FAILED);
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.DISCONNECTED);
        submitEmbed(Map.of("name", "Ada", "qty", 1), 409);
    }

    @Test
    void aRevokedAccountFoundOnSyncEndsTheCharge() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        stripe.revoked.add(accounts.findById(tenant).orElseThrow().getStripeAccountId());
        mvc.perform(post("/api/public/embed/" + token + "/payments/" + id + "/sync")).andExpect(status().isConflict());
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.UNRESOLVED);
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.DISCONNECTED);
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.CANCELLED);
    }

    @Test
    void aWorkspaceBeingDisconnectedTakesNoNewCharges() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        PaymentAccount a = accounts.findById(tenant).orElseThrow();
        a.setStatus(PaymentAccount.DISCONNECTING);
        accounts.save(a);
        submitEmbed(Map.of("name", "Bea", "qty", 1), 409);
        // A charge priced just before the switch gets no intent after it.
        FormPayment p = payments.findByInstanceId(id).orElseThrow();
        new TransactionTemplate(txManager).executeWithoutResult(t -> em
                .createNativeQuery("update luke_form_payment set status = 'CREATING', intent_id = null where id = :id")
                .setParameter("id", p.getId()).executeUpdate());
        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class, () -> paymentService.startIntent(tenant, id));
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.FAILED);
        assertThat(stripe.created).hasSize(1);
    }

    @Test
    void aStuckCreationIsFailedAndReleased() {
        // A payment row that never got an intent (the process died between the two steps).
        FormInstance inst = new FormInstance();
        inst.setTenantId(tenant);
        inst.setToken("tok-" + UUID.randomUUID());
        inst.setDefinitionCode("TICKETS");
        inst.setVersion(1);
        inst.setState(FormInstanceStates.AWAITING_PAYMENT);
        instances.save(inst);
        FormPayment p = new FormPayment();
        p.setTenantId(tenant);
        p.setInstanceId(inst.getId());
        p.setFormCode("TICKETS");
        p.setFormVersion(1);
        p.setDoor(SubmissionSource.VIA_EMBED);
        p.setFieldKey("pay");
        p.setStripeAccountId("acct_x");
        p.setAmountMinor(1500);
        p.setCurrency("USD");
        p.setMode("perUnit");
        payments.save(p);
        ageAllPayments();
        paymentService.reconcileStale();
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(FormPayment.FAILED);
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.CANCELLED);
        assertThat(stripe.created).isEmpty();
    }

    /* ── webhook ──────────────────────────────────────────────────────────── */

    private String signedEvent(String eventId, String type, String account, String objectJson) {
        String payload = "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"" + type + "\",\"livemode\":false,"
                + "\"account\":\"" + account + "\",\"api_version\":\"2024-12-18.acacia\",\"created\":1700000000,"
                + "\"data\":{\"object\":" + objectJson + "}}";
        return payload;
    }

    private String signature(String payload) throws Exception {
        long t = System.currentTimeMillis() / 1000;
        String sig = Webhook.Util.computeHmacSha256("whsec_test_connect", t + "." + payload);
        return "t=" + t + ",v1=" + sig;
    }

    @Test
    void theConnectWebhookReleasesAPaidSubmission_onceAndOnlyWhenSigned() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        String pi = stripe.onlyIntentId();
        String account = payments.findByInstanceId(id).orElseThrow().getStripeAccountId();
        stripe.setStatus(pi, "succeeded");
        String payload = signedEvent("evt_" + UUID.randomUUID(), "payment_intent.succeeded", account,
                "{\"id\":\"" + pi + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\"}");

        // A forged event is refused before anything is read.
        mvc.perform(post("/webhooks/stripe-connect").content(payload).header("Stripe-Signature", "t=1,v1=deadbeef"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/stripe-connect").content(payload)).andExpect(status().isBadRequest());
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);

        mvc.perform(post("/webhooks/stripe-connect").content(payload).header("Stripe-Signature", signature(payload)))
                .andExpect(status().isOk());
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.SUBMITTED);
        assertThat(submittedEvents(id)).isEqualTo(1);

        MvcResult again = mvc.perform(post("/webhooks/stripe-connect").content(payload).header("Stripe-Signature", signature(payload)))
                .andExpect(status().isOk()).andReturn();
        assertThat(again.getResponse().getContentAsString()).isEqualTo("duplicate");
        assertThat(submittedEvents(id)).isEqualTo(1);
    }

    @Test
    void theWebhookTrustsStripesStateNotTheEventBody() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        String pi = stripe.onlyIntentId();
        String account = payments.findByInstanceId(id).orElseThrow().getStripeAccountId();
        // The body says succeeded; Stripe (re-read) says it still needs a payment method.
        String payload = signedEvent("evt_" + UUID.randomUUID(), "payment_intent.succeeded", account,
                "{\"id\":\"" + pi + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\"}");
        mvc.perform(post("/webhooks/stripe-connect").content(payload).header("Stripe-Signature", signature(payload)))
                .andExpect(status().isOk());
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        assertThat(stripe.retrieved).contains(pi);
    }

    @Test
    void theWebhookIgnoresIntentsThatArentOurs_andEventsFromTheWrongAccount() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        String pi = stripe.onlyIntentId();
        stripe.setStatus(pi, "succeeded");
        webhook.handle("payment_intent.succeeded", "acct_someone_else", JSON.readTree("{\"id\":\"" + pi + "\"}"));
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        // The tenant's own, non-Lukeflow payment: unknown intent, ignored without error.
        webhook.handle("payment_intent.succeeded", "acct_x", JSON.readTree("{\"id\":\"pi_not_ours\"}"));
    }

    @Test
    void refundsAndAccountEventsAreRecorded() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 2), 200).path("instanceId").asText();
        String pi = stripe.onlyIntentId();
        String account = payments.findByInstanceId(id).orElseThrow().getStripeAccountId();
        webhook.handle("charge.refunded", account, JSON.readTree("{\"id\":\"ch_1\",\"payment_intent\":\"" + pi + "\",\"amount_refunded\":1000}"));
        assertThat(payments.findByInstanceId(id).orElseThrow().getAmountRefunded()).isEqualTo(1000);

        stripe.account = new StripeConnectGateway.AccountSnapshot(account, false, true, "Fake Co", "usd", "US");
        webhook.handle("account.updated", account, JSON.readTree("{\"id\":\"" + account + "\"}"));
        assertThat(accounts.findById(tenant).orElseThrow().isChargesEnabled()).isFalse();

        // A late deauthorization event while access still works is ignored…
        webhook.handle("account.application.deauthorized", account, JSON.readTree("{\"id\":\"ca_test\"}"));
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.CONNECTED);

        // …a real one disconnects the account and ends its open charge, releasing the submission.
        stripe.revoked.add(account);
        webhook.handle("account.application.deauthorized", account, JSON.readTree("{\"id\":\"ca_test\"}"));
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.DISCONNECTED);
        assertThat(payments.findByInstanceId(id).orElseThrow().getStatus()).isEqualTo(FormPayment.UNRESOLVED);
        assertThat(instance(id).getState()).isEqualTo(FormInstanceStates.CANCELLED);
    }

    @Test
    void aTransientFailureCheckingADeauthorizationIsRetriedByStripe() throws Exception {
        String account = accounts.findById(tenant).orElseThrow().getStripeAccountId();
        stripe.failAccountRead = new StripeConnectGateway.GatewayException("down", "api_error", false, null);
        org.junit.jupiter.api.Assertions.assertThrows(StripeConnectGateway.GatewayException.class,
                () -> webhook.handle("account.application.deauthorized", account, JSON.readTree("{}")));
        assertThat(accounts.findById(tenant).orElseThrow().getStatus()).isEqualTo(PaymentAccount.CONNECTED);
    }

    @Test
    void purgingATenantCancelsItsOpenChargesAndRevokesAccessAfterCommit() throws Exception {
        String id = submitEmbed(Map.of("name", "Ada", "qty", 1), 200).path("instanceId").asText();
        String pi = stripe.onlyIntentId();
        String account = payments.findByInstanceId(id).orElseThrow().getStripeAccountId();
        webhookEvents.save(new PaymentWebhookEvent("evt_" + UUID.randomUUID(), "account.updated", account));
        admin.purgeTenant(tenant);
        assertThat(payments.findByInstanceId(id)).isEmpty();
        assertThat(accounts.findById(tenant)).isEmpty();
        assertThat(stripe.cancelled).containsExactly(pi);
        assertThat(stripe.deauthorized).containsExactly(account);
        assertThat(webhookEvents.findAll().stream().filter(e -> account.equals(e.getAccountId()))).isEmpty();
    }

    /* ── recipient (respond) door ─────────────────────────────────────────── */

    private FormInstance outboundInstance() {
        form(tenant, "INVOICE", FormDefinition.KIND_OUTBOUND);
        FormInstance inst = new FormInstance();
        inst.setTenantId(tenant);
        inst.setToken("resp-" + UUID.randomUUID());
        inst.setDefinitionCode("INVOICE");
        inst.setVersion(1);
        inst.setState(FormInstanceStates.SENT);
        inst.setRecipient(Map.of("email", "payer@example.com"));
        return instances.save(inst);
    }

    private JsonNode respond(String method, String path, String access, Object body, int expectStatus) throws Exception {
        var req = (method.equals("GET") ? get(path) : post(path)).header("Authorization", "Bearer " + access);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        MvcResult r = mvc.perform(req).andExpect(status().is(expectStatus)).andReturn();
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? JSON.createObjectNode() : JSON.readTree(s);
    }

    @Test
    void aRecipientPaysAndCanResume_andAnAbandonedFormReopens() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());

        JsonNode submitted = respond("POST", base + "/submit", access,
                Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        assertThat(submitted.path("state").asText()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        String secret = submitted.path("payment").path("clientSecret").asText();
        assertThat(submitted.path("payment").path("amountMinor").asLong()).isEqualTo(3000);

        // The payer reloads: the form still renders (with its state) and the charge resumes, same intent.
        JsonNode rendered = respond("GET", base, access, null, 200);
        assertThat(rendered.path("state").asText()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        assertThat(rendered.path("payment").path("available").asBoolean()).isTrue();
        JsonNode resumed = respond("POST", base + "/payment", access, null, 200);
        assertThat(resumed.path("clientSecret").asText()).isEqualTo(secret);
        assertThat(stripe.intents).hasSize(1);
        // Editing is closed while payment is pending.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base)
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":{\"qty\":1}}"))
                .andExpect(status().isConflict());

        // Abandoned: cancelled at Stripe, and the recipient's form reopens (answers kept).
        ageAllPayments();
        paymentService.reconcileStale();
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
        assertThat(instance(inst.getId()).getData()).containsEntry("name", "Bo");

        // They try again: a NEW charge, and this time it succeeds.
        JsonNode retry = respond("POST", base + "/submit", access,
                Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        assertThat(stripe.intents).hasSize(2);
        String pi2 = stripe.intents.keySet().stream().filter(k -> !stripe.cancelled.contains(k)).findFirst().orElseThrow();
        assertThat(retry.path("payment").path("clientSecret").asText()).startsWith(pi2);
        stripe.setStatus(pi2, "succeeded");
        JsonNode synced = respond("POST", base + "/payment/sync", access, null, 200);
        assertThat(synced.path("status").asText()).isEqualTo("succeeded");
        assertThat(synced.path("state").asText()).isEqualTo(FormInstanceStates.SUBMITTED);
    }

    @Test
    void aPaidFormReturnedForCorrectionIsNotChargedAgain() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        stripe.setStatus(stripe.onlyIntentId(), "succeeded");
        respond("POST", base + "/payment/sync", access, null, 200);
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.SUBMITTED);

        // Staff return it for correction.
        FormInstance returned = instance(inst.getId());
        returned.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(returned);

        // Same amount → submitted straight away, no second charge.
        JsonNode again = respond("POST", base + "/submit", access,
                Map.of("data", Map.of("name", "Bob", "qty", 2), "consentAgreed", true), 200);
        assertThat(again.path("state").asText()).isEqualTo(FormInstanceStates.SUBMITTED);
        assertThat(again.has("payment")).isFalse();
        assertThat(stripe.created).hasSize(1);
        assertThat(payValue(inst.getId())).containsEntry("status", "paid");

        // A different amount after payment is refused rather than silently re-charged or under-charged.
        returned = instance(inst.getId());
        returned.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(returned);
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bob", "qty", 3), "consentAgreed", true), 409);
        assertThat(stripe.created).hasSize(1);

        // The render tells the page not to ask for a card again.
        JsonNode rendered = respond("GET", base, access, null, 200);
        assertThat(rendered.path("payment").path("alreadyPaid").asBoolean()).isTrue();
        assertThat(rendered.path("payment").path("paidAmountMinor").asLong()).isEqualTo(3000);
    }

    @Test
    void aRecipientWhoseChargeCouldntStartCanRetry_orFillAgainIfItWasRefused() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        stripe.failCreate = new StripeConnectGateway.GatewayException("down", "api_error", false, null);
        JsonNode res = respond("POST", base + "/submit", access,
                Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        assertThat(res.path("state").asText()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        assertThat(res.path("payment").isNull()).isTrue();
        assertThat(res.path("paymentRetryable").asBoolean()).isTrue();
        stripe.failCreate = null;
        assertThat(respond("POST", base + "/payment", access, null, 200).path("clientSecret").asText()).endsWith("_secret_x");

        FormInstance other = outboundInstance2();
        String base2 = "/api/public/form-instances/" + other.getToken();
        String access2 = recipientTokens.sign(other.getToken(), System.currentTimeMillis());
        stripe.failCreate = new StripeConnectGateway.GatewayException("too small", "amount_too_small", true, null);
        JsonNode refused = respond("POST", base2 + "/submit", access2,
                Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        assertThat(refused.path("paymentRetryable").asBoolean()).isFalse();
        assertThat(refused.path("state").asText()).isEqualTo(FormInstanceStates.IN_PROGRESS);
    }

    private FormInstance outboundInstance2() {
        FormInstance inst = new FormInstance();
        inst.setTenantId(tenant);
        inst.setToken("resp-" + UUID.randomUUID());
        inst.setDefinitionCode("INVOICE");
        inst.setVersion(1);
        inst.setState(FormInstanceStates.SENT);
        inst.setRecipient(Map.of("email", "payer2@example.com"));
        return instances.save(inst);
    }

    @Test
    void aRefundedFormReturnedForCorrectionIsNotReleasedAsPaid() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        String pi = stripe.onlyIntentId();
        stripe.setStatus(pi, "succeeded");
        respond("POST", base + "/payment/sync", access, null, 200);
        webhook.handle("charge.refunded", payments.findByInstanceId(inst.getId()).orElseThrow().getStripeAccountId(),
                JSON.readTree("{\"id\":\"ch_1\",\"payment_intent\":\"" + pi + "\",\"amount_refunded\":3000}"));

        FormInstance returned = instance(inst.getId());
        returned.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(returned);
        long outboxBefore = outbox.count();
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 409);
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
        assertThat(respond("GET", base, access, null, 200).path("payment").path("alreadyPaid").asBoolean()).isFalse();
    }

    @Test
    void aRefundTheWebhookMissedIsCaughtBeforeAResubmission() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        String pi = stripe.onlyIntentId();
        stripe.setStatus(pi, "succeeded");
        respond("POST", base + "/payment/sync", access, null, 200);
        FormInstance returned = instance(inst.getId());
        returned.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(returned);

        // Stripe can't be asked: the resubmission waits rather than trusting the stored row.
        stripe.failChargeState = new StripeConnectGateway.GatewayException("down", "api_error", false, null);
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 502);
        stripe.failChargeState = null;

        // Refunded in the dashboard; no webhook arrived.
        stripe.chargeStates.put(pi, new StripeConnectGateway.ChargeState(3000, false));
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 409);
        assertThat(payments.findByInstanceId(inst.getId()).orElseThrow().getAmountRefunded()).isEqualTo(3000);
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
    }

    @Test
    void aDisputeIsRecordedAndBlocksAResubmission() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        String pi = stripe.onlyIntentId();
        stripe.setStatus(pi, "succeeded");
        respond("POST", base + "/payment/sync", access, null, 200);
        String account = payments.findByInstanceId(inst.getId()).orElseThrow().getStripeAccountId();
        stripe.chargeStates.put(pi, new StripeConnectGateway.ChargeState(0, true));
        webhook.handle("charge.dispute.created", account, JSON.readTree("{\"id\":\"dp_1\",\"payment_intent\":\"" + pi + "\"}"));
        assertThat(payments.findByInstanceId(inst.getId()).orElseThrow().isDisputed()).isTrue();
        assertThat(audits("payments.disputed", tenant)).isEqualTo(1);

        FormInstance returned = instance(inst.getId());
        returned.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(returned);
        assertThat(respond("GET", base, access, null, 200).path("payment").path("alreadyPaid").asBoolean()).isFalse();
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 409);
    }

    @Test
    void aPreparerSetQuantityPricesTheRecipientsCharge() throws Exception {
        FormDefinition f = forms.findByTenantIdAndCode(tenant, "INVOICE").orElseGet(() -> form(tenant, "INVOICE", FormDefinition.KIND_OUTBOUND));
        f.setOutboundRolesJson("{\"qty\":\"PREPARER\"}");
        forms.save(f);
        FormInstance inst = new FormInstance();
        inst.setTenantId(tenant);
        inst.setToken("resp-" + UUID.randomUUID());
        inst.setDefinitionCode("INVOICE");
        inst.setVersion(1);
        inst.setState(FormInstanceStates.SENT);
        inst.setRecipient(Map.of("email", "payer@example.com"));
        inst.setPrefill(Map.of("qty", 4));
        instances.save(inst);
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        // The recipient can't send the locked quantity — and a forged one is ignored.
        JsonNode res = respond("POST", base + "/submit", access,
                Map.of("data", Map.of("name", "Bo", "qty", 1), "consentAgreed", true), 200);
        assertThat(res.path("state").asText()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
        assertThat(res.path("payment").path("amountMinor").asLong()).isEqualTo(6000);
        assertThat(instance(inst.getId()).getData()).containsEntry("qty", 4);
    }

    @Test
    void aResumedChargeRestartsTheAbandonmentClock() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        String access = recipientTokens.sign(inst.getToken(), System.currentTimeMillis());
        respond("POST", base + "/submit", access, Map.of("data", Map.of("name", "Bo", "qty", 2), "consentAgreed", true), 200);
        ageAllPayments();
        respond("POST", base + "/payment", access, null, 200);
        paymentService.reconcileStale();
        assertThat(stripe.cancelled).isEmpty();
        assertThat(instance(inst.getId()).getState()).isEqualTo(FormInstanceStates.AWAITING_PAYMENT);
    }

    @Test
    void anAttemptWhoseIntentMayStillTakeMoneyIsNeverReplaced() {
        FormPayment p = new FormPayment();
        p.setIntentId("pi_1");
        for (String s : List.of(FormPayment.CREATING, FormPayment.REQUIRES_PAYMENT, FormPayment.PROCESSING,
                FormPayment.SUCCEEDED, FormPayment.FAILED, FormPayment.UNRESOLVED)) {
            p.setStatus(s);
            assertThat(FormPaymentService.replaceable(p)).as(s).isFalse();
        }
        p.setStatus(FormPayment.CANCELED);
        assertThat(FormPaymentService.replaceable(p)).isTrue();
        p.setIntentId(null);
        p.setStatus(FormPayment.FAILED);
        assertThat(FormPaymentService.replaceable(p)).isTrue();
    }

    @Test
    void aLongChargeDescriptionIsTruncatedNotFatal() {
        FormPayment p = new FormPayment();
        p.setDescription("x".repeat(1500));
        assertThat(p.getDescription()).hasSize(1000);
    }

    @Test
    void publicPaymentEndpointsRequireTheRecipientsSession() throws Exception {
        FormInstance inst = outboundInstance();
        String base = "/api/public/form-instances/" + inst.getToken();
        mvc.perform(post(base + "/payment")).andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/payment/sync").header("Authorization", "Bearer nonsense")).andExpect(status().isUnauthorized());
        List<FormPayment> none = payments.findAll().stream().filter(p -> p.getInstanceId().equals(inst.getId())).toList();
        assertThat(none).isEmpty();
    }
}
