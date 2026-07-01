package com.luke.engine.capability.email;

import com.luke.engine.workflow.CapabilityActionException;
import com.luke.engine.workflow.CapabilityActionHandler;
import com.luke.engine.workflow.Placeholders;
import com.luke.engine.workflow.WorkflowNode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The outbound-rail handler for the EMAIL capability (WF-11 follow-up): a workflow
 * {@code email / send} action turns into an {@link EmailService} send. Node inputs support
 * {@code to / from / cc / bcc / replyTo / subject / htmlBody / textBody} for raw sends and
 * {@code template (alias) / templateId / templateModel} for template sends; string inputs
 * may contain {@code {{ placeholders }}} resolved against the process variables.
 */
@Component
public class EmailActionHandler implements CapabilityActionHandler {

    private final EmailService emails;

    public EmailActionHandler(EmailService emails) {
        this.emails = emails;
    }

    @Override
    public String capability() {
        return "email";
    }

    @Override
    public Object execute(String tenantId, WorkflowNode node, Map<String, Object> variables) {
        Map<String, Object> in = node.input() != null ? node.input() : Map.of();

        String to = Placeholders.resolve(str(in, "to"), variables);
        if (to == null || to.isBlank()) {
            throw new CapabilityActionException("email 'send' action requires a 'to' recipient");
        }
        String templateAlias = str(in, "template") != null ? str(in, "template") : str(in, "templateAlias");
        Long templateId = num(in, "templateId");

        EmailRequest req = new EmailRequest(
                Placeholders.resolve(str(in, "from"), variables),
                to,
                str(in, "cc"),
                str(in, "bcc"),
                str(in, "replyTo"),
                Placeholders.resolve(str(in, "subject"), variables),
                Placeholders.resolve(str(in, "htmlBody"), variables),
                Placeholders.resolve(str(in, "textBody"), variables),
                templateId,
                templateAlias,
                map(in, "templateModel"),
                null,
                null,
                null,
                null);

        EmailMessage msg = (templateAlias != null || templateId != null)
                ? emails.sendTemplate(tenantId, "workflow", req)
                : emails.sendRaw(tenantId, "workflow", req);

        return Map.of("emailId", String.valueOf(msg.getId()));
    }

    private static String str(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long num(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v instanceof Number n ? n.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
