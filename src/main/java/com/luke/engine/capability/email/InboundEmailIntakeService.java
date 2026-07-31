package com.luke.engine.capability.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.stereotype.Service;

/**
 * Turns a Postmark inbound webhook payload into stored mail and, usually, a task.
 *
 * <p>The pipeline, in order:
 * <ol>
 *   <li><b>Dedup</b> on Postmark's {@code MessageID}. A redelivered webhook must not become a
 *       second review task — duplicated work for a human is the visible symptom of the one
 *       failure mode a retrying provider guarantees.</li>
 *   <li><b>Store</b> the envelope ({@link EmailMessage}) and the content ({@link InboundEmail}).
 *       Content is stored whether or not the recipient matched a box, so nothing a tenant was
 *       sent is ever silently dropped.</li>
 *   <li><b>Route</b> via {@link EmailRoutingRuleService} — who the task goes to, how urgent,
 *       which process, or no task at all.</li>
 *   <li><b>Start</b> the review process (default or rule-chosen) and correlate any user-designed
 *       workflows subscribed to {@code email.inbound}.</li>
 * </ol>
 *
 * <p>Every step after storage is best-effort: once the message is persisted the webhook has
 * succeeded, and a routing or workflow fault must never make Postmark retry mail we already hold.
 */
@Service
public class InboundEmailIntakeService {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailIntakeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Cap on a stored body. Postmark accepts inbound up to 35 MB; a single HTML body in the
     * megabytes is real (newsletters, long quoted threads) and putting it unbounded into a row
     * that the UI later renders is how one message degrades the whole inbox. Truncation is
     * marked so a reader can tell the difference between a short email and a clipped one.
     */
    static final int BODY_MAX = 512 * 1024;
    static final String TRUNCATION_MARKER = "\n\n[… truncated by Lukeflow: message exceeded "
            + (BODY_MAX / 1024) + " KB …]";

    /** Cap on the sanitized JSON handed to the process engine as a variable. */
    static final int EVENT_VAR_MAX = 32 * 1024;

    private final EmailServerRepository servers;
    private final EmailBoxService boxes;
    private final EmailMessageRepository messages;
    private final InboundEmailRepository inbound;
    private final EmailRoutingRuleService routing;
    private final EmailEventCorrelator correlator;
    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    /** The default per-tenant inbox process key (see EmailInboxProcess.bpmn). */
    @Value("${luke.email.inbox-process-key:EmailInboxProcess}")
    private String inboxProcessKey;

    public InboundEmailIntakeService(EmailServerRepository servers, EmailBoxService boxes,
            EmailMessageRepository messages, InboundEmailRepository inbound,
            EmailRoutingRuleService routing, EmailEventCorrelator correlator,
            RuntimeService runtimeService, IdentityService identityService) {
        this.servers = servers;
        this.boxes = boxes;
        this.messages = messages;
        this.inbound = inbound;
        this.routing = routing;
        this.correlator = correlator;
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    /** What the webhook reports back (also the shape asserted by the tests). */
    public record IntakeResult(boolean received, boolean matched, boolean duplicate,
                               String messageId, boolean taskCreated, int workflows,
                               String matchedRule) {}

    /** Resolve the unguessable inbound token to a tenant's server. */
    public Optional<EmailServer> serverForToken(String token) {
        return servers.findByInboundHookToken(token);
    }

    public IntakeResult intake(EmailServer server, JsonNode payload) {
        String tenantId = server.getTenantId();

        String recipient = firstNonBlank(text(payload, "OriginalRecipient"), text(payload, "To"));
        String mailboxHash = firstNonBlank(text(payload, "MailboxHash"), toFullMailboxHash(payload));
        String from = firstNonBlank(fromFull(payload, "Email"), text(payload, "From"));
        String subject = text(payload, "Subject");
        String postmarkMessageId = text(payload, "MessageID");

        // 1 ── dedup. Cheap, and the only thing standing between a provider retry and a second
        // task on someone's queue. The unique index in V23 closes the concurrent race.
        if (postmarkMessageId != null && !postmarkMessageId.isBlank()) {
            Optional<EmailMessage> seen = messages
                    .findFirstByTenantIdAndDirectionAndPostmarkMessageId(tenantId, "INBOUND", postmarkMessageId);
            if (seen.isPresent()) {
                log.info("Inbound email {} for tenant {} already received — ignoring redelivery",
                        postmarkMessageId, tenantId);
                return new IntakeResult(true, true, true, seen.get().getId(), false, 0, null);
            }
        }

        Optional<EmailBox> box = boxes.resolveInbound(tenantId, recipient, mailboxHash);
        String boxId = box.map(EmailBox::getId).orElse(null);
        String boxAddress = box.map(EmailBox::getAddress).orElse(recipient);

        String textBody = clamp(text(payload, "TextBody"));
        String htmlBody = clamp(text(payload, "HtmlBody"));

        // 2 ── store. Under the tenant's identity scope: a webhook carries no auth context.
        String messageId;
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            messageId = store(tenantId, box, boxAddress, recipient, mailboxHash, from, subject,
                    postmarkMessageId, textBody, htmlBody, payload);
        } finally {
            identityService.clearAuthentication();
        }

        // Nothing beyond this point may cost the tenant the message.
        boolean wantsProcessing = box.isPresent() && box.get().isWorkflowTrigger();
        if (!wantsProcessing) {
            log.info("Inbound email for tenant {} → {} stored (matched={}, no processing)",
                    tenantId, recipient, box.isPresent());
            return new IntakeResult(true, box.isPresent(), false, messageId, false, 0, null);
        }

        // 3 ── route.
        EmailRoutingRuleService.Routing decision = routing.resolve(tenantId, boxId,
                new EmailRoutingRuleService.Candidate(from, subject, recipient,
                        textBody != null ? textBody : htmlBody));

        // 4 ── start + correlate.
        String eventJson = sanitizedEvent(payload);
        boolean taskCreated = false;
        identityService.setAuthentication(null, null, List.of(tenantId));
        try {
            if (!decision.suppressTask()) {
                taskCreated = startInboxProcess(tenantId, boxAddress, messageId, from, subject,
                        eventJson, decision);
            }
        } finally {
            identityService.clearAuthentication();
        }

        int workflows = 0;
        try {
            workflows = correlator.correlate(tenantId, boxAddress, messageId, from, subject, eventJson);
        } catch (RuntimeException e) {
            log.warn("Inbound-email workflow correlation failed for tenant {} box {}: {}",
                    tenantId, boxAddress, e.getMessage());
        }

        log.info("Inbound email for tenant {} → {} (matched={}, task={}, workflows={}, rule={})",
                tenantId, recipient, box.isPresent(), taskCreated, workflows, decision.matchedRuleName());
        return new IntakeResult(true, box.isPresent(), false, messageId, taskCreated, workflows,
                decision.matchedRuleName());
    }

    /** Persist the envelope + the content. Returns the new message id. */
    private String store(String tenantId, Optional<EmailBox> box, String boxAddress, String recipient,
            String mailboxHash, String from, String subject, String postmarkMessageId,
            String textBody, String htmlBody, JsonNode payload) {

        EmailMessage msg = new EmailMessage();
        msg.setTenantId(tenantId);
        msg.setDirection("INBOUND");
        msg.setStatus(EmailStatus.RECEIVED);
        msg.setFromAddress(from != null ? from : "unknown");
        msg.setToAddress(recipient != null ? recipient : "unknown");
        msg.setSubject(subject);
        msg.setPostmarkMessageId(postmarkMessageId);
        String messageId = messages.save(msg).getId();

        InboundEmail content = new InboundEmail();
        content.setId(messageId);
        content.setTenantId(tenantId);
        content.setBoxId(box.map(EmailBox::getId).orElse(null));
        content.setBoxAddress(boxAddress);
        content.setMailboxHash(mailboxHash);
        content.setFromName(fromFull(payload, "Name"));
        content.setToFull(truncate(text(payload, "To"), 1000));
        content.setCcAddresses(truncate(text(payload, "Cc"), 1000));
        content.setReplyTo(truncate(text(payload, "ReplyTo"), 255));
        content.setTextBody(textBody);
        content.setHtmlBody(htmlBody);
        content.setStrippedTextReply(clamp(text(payload, "StrippedTextReply")));
        // Postmark's MessageID is its OWN id; the RFC 5322 Message-ID lives in the headers and is
        // what reply threading needs.
        content.setMessageIdHeader(truncate(header(payload, "Message-ID"), 998));
        content.setInReplyTo(truncate(header(payload, "In-Reply-To"), 998));
        content.setHeaders(writeJson(payload.get("Headers")));

        ArrayNode attachments = attachmentMetadata(payload);
        content.setAttachments(writeJson(attachments));
        content.setAttachmentCount(attachments.size());
        inbound.save(content);
        return messageId;
    }

    /** Start the review process for one message, carrying the routing decision. */
    private boolean startInboxProcess(String tenantId, String boxAddress, String messageId, String from,
            String subject, String eventJson, EmailRoutingRuleService.Routing decision) {
        String processKey = decision.processKey() != null ? decision.processKey() : inboxProcessKey;
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("emailMessageId", messageId);
            vars.put("emailBox", boxAddress);
            vars.put("emailFrom", from);
            vars.put("emailSubject", subject);
            vars.put("emailEvent", eventJson);
            // Consumed by EmailTaskRoutingListener when the review task is created.
            vars.put("emailTaskName", decision.taskName());
            vars.put("emailAssignee", decision.assignee());
            vars.put("emailCandidateGroup", decision.candidateGroup());
            vars.put("emailPriority", decision.priority());
            vars.put("emailRoutingRule", decision.matchedRuleName());
            runtimeService.createProcessInstanceByKey(processKey)
                    .processDefinitionTenantId(tenantId)
                    .businessKey("email-inbox-" + messageId)
                    .setVariables(vars)
                    .execute();
            return true;
        } catch (RuntimeException e) {
            // A rule naming a process that isn't deployed lands here. Log loudly — the tenant
            // authored something that silently isn't running — but keep the message.
            log.warn("Email inbox process '{}' did not start for tenant {} box {}: {}",
                    processKey, tenantId, boxAddress, e.getMessage());
            return false;
        }
    }

    // ── payload handling ─────────────────────────────────────────────────────

    /**
     * Attachment METADATA only — name, type, length.
     *
     * <p>Postmark inlines every attachment as base64 in this payload. Keeping those bytes would
     * put multi-megabyte blobs in a table row AND (before this) in a Camunda process variable,
     * where the engine copies them on every migration. File bytes belong in object storage.
     */
    static ArrayNode attachmentMetadata(JsonNode payload) {
        ArrayNode out = MAPPER.createArrayNode();
        JsonNode arr = payload != null ? payload.get("Attachments") : null;
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode a : arr) {
            ObjectNode meta = out.addObject();
            meta.put("name", a.hasNonNull("Name") ? a.get("Name").asText() : null);
            meta.put("contentType", a.hasNonNull("ContentType") ? a.get("ContentType").asText() : null);
            meta.put("contentLength", a.hasNonNull("ContentLength") ? a.get("ContentLength").asLong() : 0L);
        }
        return out;
    }

    /**
     * The payload as a process variable, with attachment CONTENT removed and the whole thing
     * bounded. Camunda spills any string over 4000 chars into a byte array; a raw payload with
     * a 10 MB base64 attachment became a 10 MB variable, per message, forever.
     */
    static String sanitizedEvent(JsonNode payload) {
        if (payload == null) return "{}";
        JsonNode copy = payload.deepCopy();
        JsonNode arr = copy.get("Attachments");
        if (arr != null && arr.isArray()) {
            for (JsonNode a : arr) {
                if (a instanceof ObjectNode o) o.remove("Content");
            }
        }
        String json = copy.toString();
        return json.length() <= EVENT_VAR_MAX ? json : json.substring(0, EVENT_VAR_MAX);
    }

    private static String clamp(String body) {
        if (body == null) return null;
        return body.length() <= BODY_MAX ? body : body.substring(0, BODY_MAX) + TRUNCATION_MARKER;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String writeJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    /** Read a named RFC header out of Postmark's {@code Headers} array (case-insensitive). */
    static String header(JsonNode payload, String name) {
        JsonNode arr = payload != null ? payload.get("Headers") : null;
        if (arr == null || !arr.isArray()) return null;
        for (JsonNode h : arr) {
            if (h.hasNonNull("Name") && h.get("Name").asText().equalsIgnoreCase(name)) {
                return h.hasNonNull("Value") ? h.get("Value").asText() : null;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node != null ? node.get(field) : null;
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static String fromFull(JsonNode node, String field) {
        JsonNode f = node != null ? node.get("FromFull") : null;
        return f != null && f.hasNonNull(field) ? f.get(field).asText() : null;
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
