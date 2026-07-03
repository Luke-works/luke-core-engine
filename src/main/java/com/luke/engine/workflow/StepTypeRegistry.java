package com.luke.engine.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The in-process aggregator of {@link StepTypeDescriptor}s served to the builder
 * as the step-type catalog (WF-2). Capabilities {@link #register} their step types
 * at startup; the builder fetches the merged list via {@link WorkflowCatalogController}.
 *
 * <p>V1 seeds a reference set directly (email/forms/integrations) so the catalog is
 * useful before each capability wires its own descriptors. Registration is
 * last-write-wins per id; iteration order is registration order.
 */
@Component
public class StepTypeRegistry {

    private final Map<String, StepTypeDescriptor> byId = new LinkedHashMap<>();

    public StepTypeRegistry() {
        // Reference descriptors — the ones the golden fixture resolves against.
        // Replaced/augmented as each capability publishes its own (WF-2 follow-ups).
        // Forms initiators — map 1:1 to real FormInstance lifecycle states
        // (see FormInstanceStates). NB: there is no "approved"/"rejected" form state —
        // approval/rejection is a review-task OUTCOME evaluated inside a running workflow
        // (see forms.review), not a starting event. IN_PROGRESS is omitted (autosave noise).
        register(new StepTypeDescriptor("forms.submitted", "Form submitted", "FORMS", "trigger", "Forms"));
        register(new StepTypeDescriptor("forms.processed", "Form processed", "FORMS", "trigger", "Forms"));
        register(new StepTypeDescriptor("forms.created", "Form created", "FORMS", "trigger", "Forms"));
        register(new StepTypeDescriptor("forms.sent", "Form invitation sent", "FORMS", "trigger", "Forms"));
        register(new StepTypeDescriptor("forms.opened", "Form opened", "FORMS", "trigger", "Forms"));
        register(new StepTypeDescriptor("forms.expired", "Form expired", "FORMS", "trigger", "Forms"));
        register(new StepTypeDescriptor("forms.cancelled", "Form cancelled", "FORMS", "trigger", "Forms"));
        register(new StepTypeDescriptor("forms.review", "Review task", "FORMS", "task", "Forms"));
        // Email initiators (native trigger lifecycle — delivery + engagement events).
        register(new StepTypeDescriptor("email.received", "Email received", "EMAIL", "trigger", "Email"));
        register(new StepTypeDescriptor("email.delivered", "Email delivered", "EMAIL", "trigger", "Email"));
        register(new StepTypeDescriptor("email.opened", "Email opened", "EMAIL", "trigger", "Email"));
        register(new StepTypeDescriptor("email.link_clicked", "Email link clicked", "EMAIL", "trigger", "Email"));
        register(new StepTypeDescriptor("email.bounced", "Email bounced", "EMAIL", "trigger", "Email"));
        register(new StepTypeDescriptor("email.complained", "Email marked as spam", "EMAIL", "trigger", "Email"));
        register(new StepTypeDescriptor("email.unsubscribed", "Email unsubscribed", "EMAIL", "trigger", "Email"));
        register(new StepTypeDescriptor("email.send", "Send email", "EMAIL", "action", "Email", List.of(
                StepFieldDescriptor.req("to", "To"),
                StepFieldDescriptor.text("subject", "Subject"),
                StepFieldDescriptor.area("htmlBody", "HTML body"),
                StepFieldDescriptor.area("textBody", "Text body"),
                StepFieldDescriptor.text("template", "Template alias"),
                StepFieldDescriptor.text("from", "From"),
                StepFieldDescriptor.text("cc", "Cc"),
                StepFieldDescriptor.text("bcc", "Bcc"))));
        register(new StepTypeDescriptor("phone.call", "Place call", "PHONE", "action", "Phone", List.of(
                StepFieldDescriptor.req("customerNumber", "Customer number"),
                StepFieldDescriptor.text("assistantId", "Assistant ID"),
                StepFieldDescriptor.text("phoneNumberId", "Phone number ID"))));
        register(new StepTypeDescriptor("signatures.send", "Send for signature", "SIGNATURES", "action", "Signatures", List.of(
                StepFieldDescriptor.req("definitionCode", "Document code"),
                StepFieldDescriptor.num("version", "Version"),
                StepFieldDescriptor.num("expiresInDays", "Expires in days"))));
        register(new StepTypeDescriptor("signatures.signed", "Document signed", "SIGNATURES", "trigger", "Signatures"));
        register(new StepTypeDescriptor("integration.trigger", "Integration trigger", "INTEGRATIONS", "trigger", "Integrations"));
        register(new StepTypeDescriptor("integration.action", "Integration action", "INTEGRATIONS", "action", "Integrations"));
    }

    /** Register (or replace) a descriptor by id. */
    public void register(StepTypeDescriptor descriptor) {
        byId.put(descriptor.id(), descriptor);
    }

    /** All descriptors, in registration order. */
    public List<StepTypeDescriptor> all() {
        return new ArrayList<>(byId.values());
    }

    /**
     * Resolve the step type backing a node's {@code (capability, kind)}. Capability
     * match is case-insensitive because node JSON uses lowercase capability names
     * ({@code "email"}) while descriptors carry capability codes ({@code "EMAIL"}).
     */
    public Optional<StepTypeDescriptor> resolve(String capability, String kind) {
        if (capability == null || kind == null) return Optional.empty();
        return byId.values().stream()
                .filter(d -> kind.equals(d.kind()) && capability.equalsIgnoreCase(d.capability()))
                .findFirst();
    }
}
