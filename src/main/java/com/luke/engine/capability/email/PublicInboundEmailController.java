package com.luke.engine.capability.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.luke.engine.workflow.EmailEventCorrelator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public webhook Postmark POSTs inbound mail to. Unauthenticated by path
 * ({@code /api/public/**}); the security boundary is the unguessable per-tenant
 * {@code token} in the URL, which resolves to exactly one tenant's server. An inbound
 * message is stored (direction INBOUND) and, if it matches a registered INBOUND box with
 * {@code workflowTrigger}, correlated to a workflow via {@link EmailEventCorrelator}.
 *
 * <p>Always returns 2xx once the token is valid so Postmark doesn't retry a message we've
 * already accepted (an unmatched recipient is still stored for the tenant's inbox).
 */
@RestController
@RequestMapping("/api/public/email")
public class PublicInboundEmailController {

    private static final Logger log = LoggerFactory.getLogger(PublicInboundEmailController.class);

    private final EmailServerRepository servers;
    private final EmailBoxService boxes;
    private final EmailMessageRepository messages;
    private final EmailEventCorrelator correlator;
    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    /** The default per-tenant inbox process key (see EmailInboxProcess.bpmn / EmailInboxProcessDeployer). */
    @Value("${luke.email.inbox-process-key:EmailInboxProcess}")
    private String inboxProcessKey;

    public PublicInboundEmailController(EmailServerRepository servers, EmailBoxService boxes,
            EmailMessageRepository messages, EmailEventCorrelator correlator,
            RuntimeService runtimeService, IdentityService identityService) {
        this.servers = servers;
        this.boxes = boxes;
        this.messages = messages;
        this.correlator = correlator;
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    @PostMapping("/inbound/{token}")
    public Map<String, Object> inbound(@PathVariable String token, @RequestBody JsonNode payload) {
        EmailServer server = servers.findByInboundHookToken(token).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown inbound token"));
        String tenantId = server.getTenantId();

        String recipient = firstNonBlank(text(payload, "OriginalRecipient"), text(payload, "To"));
        String mailboxHash = firstNonBlank(text(payload, "MailboxHash"), toFullMailboxHash(payload));
        String from = firstNonBlank(fromFullEmail(payload), text(payload, "From"));
        String subject = text(payload, "Subject");
        String postmarkMessageId = text(payload, "MessageID");

        Optional<EmailBox> box = boxes.resolveInbound(tenantId, recipient, mailboxHash);

        boolean process = box.isPresent() && box.get().isWorkflowTrigger();

        // Persist the inbound message + (if the box wants processing) start the default per-tenant
        // inbox process — both under the tenant's identity scope (webhook has no auth context).
        String messageId;
        boolean startedInbox = false;
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            EmailMessage msg = new EmailMessage();
            msg.setTenantId(tenantId);
            msg.setDirection("INBOUND");
            msg.setStatus(EmailStatus.RECEIVED);
            msg.setFromAddress(from != null ? from : "unknown");
            msg.setToAddress(recipient != null ? recipient : "unknown");
            msg.setSubject(subject);
            msg.setPostmarkMessageId(postmarkMessageId);
            messageId = messages.save(msg).getId();
            if (process) {
                startedInbox = startInboxProcess(tenantId, box.get().getAddress(), messageId, from, subject, payload.toString());
            }
        } finally {
            identityService.clearAuthentication();
        }

        // User-designed workflows subscribed to email.inbound (in addition to the default inbox).
        int workflows = 0;
        if (process) {
            try {
                workflows = correlator.correlate(tenantId, box.get().getAddress(), messageId, from, subject, payload.toString());
            } catch (RuntimeException e) {
                // Never fail the webhook on a workflow error — the message is already stored.
                log.warn("Inbound-email workflow correlation failed for tenant {} box {}: {}",
                        tenantId, box.get().getAddress(), e.getMessage());
            }
        }
        log.info("Inbound email for tenant {} → {} (matched={}, inbox={}, workflows={})",
                tenantId, recipient, box.isPresent(), startedInbox, workflows);
        return Map.of("received", true, "matched", box.isPresent(), "inboxProcess", startedInbox, "workflows", workflows);
    }

    /** Start the default per-tenant inbox process for one inbound message (best-effort). */
    private boolean startInboxProcess(String tenantId, String boxAddress, String messageId, String from, String subject, String rawJson) {
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("emailMessageId", messageId);
            vars.put("emailBox", boxAddress);
            vars.put("emailFrom", from);
            vars.put("emailSubject", subject);
            vars.put("emailEvent", rawJson);
            runtimeService.createProcessInstanceByKey(inboxProcessKey)
                    .processDefinitionTenantId(tenantId)
                    .businessKey("email-inbox-" + messageId)
                    .setVariables(vars)
                    .execute();
            return true;
        } catch (RuntimeException e) {
            log.warn("Default email-inbox process start failed for tenant {} box {}: {}", tenantId, boxAddress, e.getMessage());
            return false;
        }
    }

    // ── Postmark payload helpers ─────────────────────────────────────────────

    private static String text(JsonNode node, String field) {
        JsonNode v = node != null ? node.get(field) : null;
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static String fromFullEmail(JsonNode node) {
        JsonNode f = node != null ? node.get("FromFull") : null;
        return f != null && f.hasNonNull("Email") ? f.get("Email").asText() : null;
    }

    private static String toFullMailboxHash(JsonNode node) {
        JsonNode arr = node != null ? node.get("ToFull") : null;
        if (arr != null && arr.isArray() && !arr.isEmpty() && arr.get(0).hasNonNull("MailboxHash")) {
            String h = arr.get(0).get("MailboxHash").asText();
            return h.isBlank() ? null : h;
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }
}
