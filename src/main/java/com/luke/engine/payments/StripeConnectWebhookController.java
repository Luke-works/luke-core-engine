package com.luke.engine.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stripe's CONNECT webhook — events from tenants' connected accounts. A separate endpoint (and
 * signing secret) from billing's {@code /webhooks/stripe}, which receives the platform's own events.
 *
 * <p>Mounted outside {@code /api/**} like billing's: no gateway, no default-deny — the Stripe
 * signature IS the authentication, verified before anything is read. Stripe calls the engine
 * directly.
 *
 * <p>A connected account sends ALL its events here, including its own non-Lukeflow payments; only
 * intents that map to a {@link FormPayment} are acted on. Handling never trusts the event body's
 * state: intents and accounts are re-read from Stripe before anything changes. Answers 200 for
 * anything handled or ignored, 400 for a bad signature, and lets a processing error surface as 500 so
 * Stripe retries — the event is recorded as applied only after it succeeded.
 */
@RestController
@RequestMapping("/webhooks/stripe-connect")
public class StripeConnectWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectWebhookController.class);

    private final PaymentsProperties props;
    private final FormPaymentService payments;
    private final PaymentAccountService accounts;
    private final PaymentWebhookEventRepository seen;
    private final ObjectMapper mapper;

    public StripeConnectWebhookController(PaymentsProperties props, FormPaymentService payments,
                                          PaymentAccountService accounts, PaymentWebhookEventRepository seen,
                                          ObjectMapper mapper) {
        this.props = props;
        this.payments = payments;
        this.accounts = accounts;
        this.seen = seen;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<String> receive(@RequestBody(required = false) String payload,
                                          @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        if (!props.enabled() || !props.webhookConfigured()) {
            return ResponseEntity.ok("ignored");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload == null ? "" : payload, signature, props.connectWebhookSecret());
        } catch (SignatureVerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Stripe signature");
        } catch (RuntimeException e) {
            // A missing header or an unparseable body is a forgery as far as we're concerned.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Stripe event");
        }
        if (Boolean.TRUE.equals(event.getLivemode()) != props.livemode()) {
            log.warn("Ignoring Connect event {} — its mode doesn't match this environment's keys", event.getId());
            return ResponseEntity.ok("ignored");
        }
        if (event.getId() != null && seen.existsById(event.getId())) {
            return ResponseEntity.ok("duplicate");
        }
        handle(event.getType(), event.getAccount(), dataObject(payload));
        if (event.getId() != null) {
            try {
                seen.save(new PaymentWebhookEvent(event.getId(), String.valueOf(event.getType()), event.getAccount()));
            } catch (DataIntegrityViolationException concurrent) {
                log.debug("Connect event {} recorded concurrently", event.getId());
            }
        }
        return ResponseEntity.ok("ok");
    }

    /** Dispatch a verified event. Package-private so tests can drive it without a signed payload. */
    void handle(String type, String account, JsonNode object) {
        if (type == null || object == null) return;
        String id = object.path("id").asText("");
        switch (type) {
            case "payment_intent.succeeded", "payment_intent.processing", "payment_intent.payment_failed",
                    "payment_intent.canceled", "payment_intent.requires_action" -> {
                if (!id.isEmpty()) payments.onIntentEvent(id, account);
            }
            case "charge.refunded" -> {
                String intent = object.path("payment_intent").asText("");
                if (!intent.isEmpty()) payments.onChargeRefunded(intent, object.path("amount_refunded").asLong(0));
            }
            case "charge.dispute.created", "charge.dispute.updated", "charge.dispute.closed",
                    "charge.dispute.funds_withdrawn", "charge.dispute.funds_reinstated" -> {
                String intent = object.path("payment_intent").asText("");
                if (!intent.isEmpty()) payments.onChargeDisputed(intent, account);
            }
            case "account.updated" -> {
                String acct = account != null ? account : id;
                if (acct != null && !acct.isEmpty()) accounts.onAccountUpdated(acct);
            }
            case "account.application.deauthorized" -> {
                if (account != null && !account.isEmpty()) accounts.onDeauthorized(account);
            }
            default -> log.debug("Connect event {} not handled", type);
        }
    }

    private JsonNode dataObject(String payload) {
        try {
            JsonNode obj = mapper.readTree(payload).path("data").path("object");
            return obj.isMissingNode() ? null : obj;
        } catch (Exception e) {
            return null;
        }
    }
}
