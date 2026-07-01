package com.luke.engine.capability.signature;

import com.luke.engine.capability.signature.SignatureInstanceService.CampaignInput;
import com.luke.engine.capability.signature.SignatureInstanceService.RecipientInput;
import com.luke.engine.workflow.CapabilityActionException;
import com.luke.engine.workflow.CapabilityActionHandler;
import com.luke.engine.workflow.Placeholders;
import com.luke.engine.workflow.WorkflowNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The outbound-rail handler for the SIGNATURES capability: a workflow {@code signatures / send}
 * action starts a signing campaign via {@link SignatureInstanceService}. Node inputs:
 * {@code definitionCode} (required), {@code version}, {@code name}, {@code recipients}
 * (list of {@code {signerId, name, email}}), {@code values}, {@code expiresInDays}. Recipient
 * name/email may contain {@code {{ placeholders }}} resolved against the process variables.
 */
@Component
public class SignActionHandler implements CapabilityActionHandler {

    private final SignatureInstanceService instances;

    public SignActionHandler(SignatureInstanceService instances) {
        this.instances = instances;
    }

    @Override
    public String capability() {
        return "signatures";
    }

    @Override
    public Object execute(String tenantId, WorkflowNode node, Map<String, Object> variables) {
        Map<String, Object> in = node.input() != null ? node.input() : Map.of();

        String definitionCode = str(in, "definitionCode");
        if (definitionCode == null || definitionCode.isBlank()) {
            throw new CapabilityActionException("signatures 'send' action requires a definitionCode");
        }

        List<RecipientInput> recipients = new ArrayList<>();
        if (in.get("recipients") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    recipients.add(new RecipientInput(
                            asStr(m.get("signerId")),
                            Placeholders.resolve(asStr(m.get("name")), variables),
                            Placeholders.resolve(asStr(m.get("email")), variables)));
                }
            }
        }
        if (recipients.isEmpty()) {
            throw new CapabilityActionException("signatures 'send' action requires at least one recipient");
        }

        CampaignInput input = new CampaignInput(
                definitionCode,
                num(in, "version"),
                str(in, "name"),
                recipients,
                stringValues(in.get("values")),
                num(in, "expiresInDays"));

        SignatureInstance instance = instances.startCampaign(tenantId, "workflow", input);
        return Map.of("instanceId", String.valueOf(instance.getId()));
    }

    private static Map<String, String> stringValues(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }

    private static String str(Map<String, Object> in, String key) {
        return asStr(in.get(key));
    }

    private static String asStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer num(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }
}
