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
        register(new StepTypeDescriptor("forms.submitted", "Form submitted", "FORMS", "trigger", "Forms"));
        register(new StepTypeDescriptor("forms.review", "Review task", "FORMS", "task", "Forms"));
        register(new StepTypeDescriptor("email.send", "Send email", "EMAIL", "action", "Email"));
        register(new StepTypeDescriptor("phone.call", "Place call", "PHONE", "action", "Phone"));
        register(new StepTypeDescriptor("signatures.send", "Send for signature", "SIGNATURES", "action", "Signatures"));
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
