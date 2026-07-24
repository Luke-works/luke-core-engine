package com.luke.engine.capability.form;

import com.luke.engine.recipient.RecipientItem;
import com.luke.engine.recipient.RecipientItemProvider;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Forms as the FIRST recipient-portal provider: every open outbound {@link FormInstance} assigned to
 * the recipient email becomes a {@code "form"} item in the portal. This is the only forms-specific
 * piece of the recipient hub — the identity/session layer is capability-agnostic (see
 * {@code com.luke.engine.recipient.PortalService}). Phone (for the SMS OTP channel) is taken from the
 * denormalised recipient contact on the instance.
 *
 * <p>The {@code email} passed in is the normalised (lower-cased) recipient email the session carries,
 * matching how {@code recipientEmail} is stored on write.
 */
@Component
public class FormRecipientItemProvider implements RecipientItemProvider {

    static final String TYPE = "form";

    private final FormInstanceRepository instances;
    private final FormDefinitionRepository forms;

    public FormRecipientItemProvider(FormInstanceRepository instances, FormDefinitionRepository forms) {
        this.instances = instances;
        this.forms = forms;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<RecipientItem> itemsFor(String tenantId, String email) {
        Map<String, String> nameByCode = new LinkedHashMap<>();
        List<RecipientItem> items = new ArrayList<>();
        for (FormInstance inst : openInstances(tenantId, email)) {
            String name = nameByCode.computeIfAbsent(inst.getDefinitionCode(), code ->
                    forms.findByTenantIdAndCode(tenantId, code).map(FormDefinition::getName).orElse(code));
            items.add(new RecipientItem(TYPE, inst.getToken(), name, inst.getState(),
                    epochMillis(inst.getCreatedAt()), epochMillis(inst.getExpiresAt())));
        }
        return items;
    }

    @Override
    public Optional<String> phoneFor(String tenantId, String email) {
        for (FormInstance i : openInstances(tenantId, email)) {
            if (i.getRecipientPhone() != null && !i.getRecipientPhone().isBlank()) {
                return Optional.of(i.getRecipientPhone());
            }
        }
        return Optional.empty();
    }

    private List<FormInstance> openInstances(String tenantId, String email) {
        List<FormInstance> all = instances.findByTenantIdAndRecipientEmailAndStateInOrderByCreatedAtDesc(
                tenantId, email, FormInstanceStates.OPEN);
        all.removeIf(FormInstance::isExpired); // belt-and-suspenders vs the expiry sweeper's lag
        return all;
    }

    private static Long epochMillis(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
