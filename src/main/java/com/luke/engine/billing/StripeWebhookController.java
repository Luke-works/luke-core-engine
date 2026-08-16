package com.luke.engine.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe's server-to-server webhook. Deliberately mounted OUTSIDE {@code /api/**} at
 * {@code /webhooks/stripe} because Stripe calls it directly — it carries neither a gateway act-as
 * token nor an {@code X-Tenant-Id}, so it must not sit behind the tenant/operator gates
 * ({@link com.luke.engine.config.ApiDefaultDenyFilter} guards {@code /api/*};
 * {@link com.luke.engine.capability.access.PublicGatewayAuthFilter} guards {@code /api/public/*}).
 * Its authentication is the Stripe <b>signature</b> the service verifies, not a credential.
 *
 * <p>Always answers 200 for anything it accepts or ignores, so Stripe stops retrying; only a failed
 * signature check (a forgery) is a 400, and a downstream persistence error is allowed to surface as a
 * 500 so Stripe retries the delivery.
 */
@RestController
@RequestMapping("/webhooks/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeBillingService billing;

    public StripeWebhookController(StripeBillingService billing) {
        this.billing = billing;
    }

    @PostMapping
    public ResponseEntity<String> receive(@RequestBody(required = false) String payload,
                                          @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        String handled = billing.handleWebhook(payload == null ? "" : payload, signature);
        if (handled != null) {
            log.debug("Stripe webhook processed: {}", handled);
        }
        return ResponseEntity.ok("ok");
    }
}
