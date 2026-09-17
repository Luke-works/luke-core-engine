package com.luke.engine.payments;

import com.luke.engine.capability.form.FormInstance;
import com.luke.engine.capability.form.SubmissionSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The payment step of {@code FormSubmissionService.submit} — the one choke point every submit door
 * funnels through, so no door (including one added later) can record a paid form without pricing it.
 *
 * <p>Split in two because the pending-charge row needs the instance id, which exists only once the
 * instance is saved: {@link #price} runs before the save (and may refuse the submission),
 * {@link #record} runs after it, in the same transaction.
 */
public interface PaymentGate {

    /** The data key of the schema's payment field, or empty when the form takes no payment. */
    Optional<String> paymentKey(String schemaJson);

    /**
     * Price this submission from the schema and the CLEANED answers. Throws a
     * {@link ResponseStatusException} when the submission can't be taken (wrong door, payments not
     * available, an amount the payer must fix).
     */
    PendingCharge price(FormInstance inst, String schemaJson, Map<String, Object> cleanedData, SubmissionSource source);

    /** Persist the pending charge for the just-saved instance. */
    void record(FormInstance inst, PendingCharge charge);

    /**
     * What a submission owes, as priced by the server. {@code alreadyPaid} marks a resubmission of a
     * form whose identical charge already succeeded (a paid submission returned for correction): it is
     * submitted straight away and charged nothing more.
     */
    record PendingCharge(String fieldKey, long amountMinor, String currency, Integer quantity, String mode,
                         String description, String accountId, boolean livemode, String door,
                         boolean alreadyPaid, String intentId) {}

    /** The charge record written into the submission's payment field. Mirrors form-core's PaymentValue. */
    static Map<String, Object> paymentValue(String status, long amountMinor, String currency, String intentId) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("status", status);
        v.put("amountMinor", amountMinor);
        v.put("currency", currency == null ? "" : currency);
        v.put("intentId", intentId == null ? "" : intentId);
        return v;
    }

    /**
     * Fail-closed default for contexts with no payments wiring (unit tests constructing the service by
     * hand). A form that takes a payment is REFUSED rather than silently submitted unpaid.
     */
    PaymentGate REFUSE = new PaymentGate() {
        @Override
        public Optional<String> paymentKey(String schemaJson) {
            return PaymentAmountResolver.findPaymentFields(PaymentAmountResolver.parse(schemaJson)).stream()
                    .findFirst()
                    .map(f -> {
                        String key = f.entity().path("attributes").path("key").asText("");
                        return key.isEmpty() ? f.id() : key;
                    });
        }

        @Override
        public PendingCharge price(FormInstance inst, String schemaJson, Map<String, Object> cleanedData,
                                   SubmissionSource source) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This form can't take payments right now.");
        }

        @Override
        public void record(FormInstance inst, PendingCharge charge) {
            throw new IllegalStateException("no payment gate configured");
        }
    };
}
