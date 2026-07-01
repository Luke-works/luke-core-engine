package com.luke.engine.capability.phone;

import com.luke.engine.workflow.CapabilityActionException;
import com.luke.engine.workflow.CapabilityActionHandler;
import com.luke.engine.workflow.Placeholders;
import com.luke.engine.workflow.WorkflowNode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The outbound-rail handler for the PHONE capability: a workflow {@code phone / call} action
 * places an outbound call via {@link PhoneCallService}. Node inputs support
 * {@code customerNumber} (or {@code to}), {@code phoneNumberId}, {@code assistantId},
 * {@code variableValues}, {@code metadata}; the number may contain {@code {{ placeholders }}}
 * resolved against the process variables.
 */
@Component
public class PhoneActionHandler implements CapabilityActionHandler {

    private final PhoneCallService calls;

    public PhoneActionHandler(PhoneCallService calls) {
        this.calls = calls;
    }

    @Override
    public String capability() {
        return "phone";
    }

    @Override
    public Object execute(String tenantId, WorkflowNode node, Map<String, Object> variables) {
        Map<String, Object> in = node.input() != null ? node.input() : Map.of();

        String raw = str(in, "customerNumber") != null ? str(in, "customerNumber") : str(in, "to");
        String number = Placeholders.resolve(raw, variables);
        if (number == null || number.isBlank()) {
            throw new CapabilityActionException("phone 'call' action requires a customerNumber");
        }

        OutboundCallRequest req = new OutboundCallRequest(
                number, str(in, "phoneNumberId"), str(in, "assistantId"),
                map(in, "variableValues"), map(in, "metadata"));

        PhoneCall call = calls.placeOutbound(tenantId, "workflow", req);
        return Map.of("callId", String.valueOf(call.getId()));
    }

    private static String str(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
