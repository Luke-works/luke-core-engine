package com.luke.engine.capability.email;

import com.luke.engine.capability.email.EmailServerService.SendContext;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates a send: resolve the tenant's send context (which Postmark Server
 * token to use + the verified company sender) → validate → persist a QUEUED audit
 * row → submit to Postmark → flip the row to SENT/FAILED with Postmark's outcome.
 * The row is always saved, so a delivery failure is recorded (not thrown) and the
 * caller gets back the {@link EmailMessage} whose status tells the story.
 *
 * <p>Sender ownership is enforced by {@link EmailServerService}: a company may only
 * send from its own domain. Caller mistakes (missing recipient/body/template id, or
 * a sender that isn't the company's) throw before anything is sent. The same path
 * serves the tenant API and the internal process-triggered endpoint.
 */
@Service
public class EmailService {

    private final EmailMessageRepository repository;
    private final EmailServerService servers;
    private final PostmarkClient postmark;

    public EmailService(EmailMessageRepository repository, EmailServerService servers, PostmarkClient postmark) {
        this.repository = repository;
        this.servers = servers;
        this.postmark = postmark;
    }

    /** Send a raw HTML/text email and record the outcome. */
    public EmailMessage sendRaw(String tenantId, String createdBy, EmailRequest req) {
        requireText(req.to(), "to is required");
        if (isBlank(req.subject())) throw bad("subject is required");
        if (isBlank(req.htmlBody()) && isBlank(req.textBody())) {
            throw bad("htmlBody or textBody is required");
        }
        SendContext ctx = servers.resolveSendContext(tenantId, req.from());

        EmailMessage msg = newRow(tenantId, createdBy, req, ctx);
        msg.setSubject(req.subject());
        msg.setModel(req.metadata());

        Map<String, Object> body = PostmarkClient.body();
        applyCommon(body, msg);
        PostmarkClient.put(body, "Subject", req.subject());
        PostmarkClient.put(body, "HtmlBody", req.htmlBody());
        PostmarkClient.put(body, "TextBody", req.textBody());
        PostmarkClient.put(body, "Metadata", req.metadata());

        return submit(msg, postmark.send(ctx.serverToken(), body));
    }

    /** Send a stored-template email and record the outcome. */
    public EmailMessage sendTemplate(String tenantId, String createdBy, EmailRequest req) {
        requireText(req.to(), "to is required");
        if (req.templateId() == null && isBlank(req.templateAlias())) {
            throw bad("templateId or templateAlias is required");
        }
        SendContext ctx = servers.resolveSendContext(tenantId, req.from());

        EmailMessage msg = newRow(tenantId, createdBy, req, ctx);
        msg.setTemplateId(req.templateId());
        msg.setTemplateAlias(req.templateAlias());
        msg.setModel(req.templateModel());

        Map<String, Object> body = PostmarkClient.body();
        applyCommon(body, msg);
        PostmarkClient.put(body, "TemplateId", req.templateId());
        PostmarkClient.put(body, "TemplateAlias", req.templateAlias());
        // TemplateModel must always be present (Postmark requires the key, even if empty).
        body.put("TemplateModel", req.templateModel() != null ? req.templateModel() : Map.of());

        return submit(msg, postmark.sendTemplate(ctx.serverToken(), body));
    }

    /* ── helpers ────────────────────────────────────────────── */

    private EmailMessage newRow(String tenantId, String createdBy, EmailRequest req, SendContext ctx) {
        EmailMessage msg = new EmailMessage();
        msg.setTenantId(tenantId);
        msg.setStatus(EmailStatus.QUEUED);
        msg.setFromAddress(ctx.from());
        msg.setToAddress(req.to());
        msg.setCc(req.cc());
        msg.setBcc(req.bcc());
        msg.setReplyTo(req.replyTo());
        msg.setTag(req.tag());
        msg.setMessageStream(!isBlank(req.messageStream()) ? req.messageStream().trim() : ctx.messageStream());
        msg.setContext(req.context());
        msg.setCreatedBy(createdBy);
        return repository.save(msg);
    }

    /** Shared From/To/Cc/Bcc/ReplyTo/Tag/MessageStream fields on the wire body. */
    private void applyCommon(Map<String, Object> body, EmailMessage msg) {
        PostmarkClient.put(body, "From", msg.getFromAddress());
        PostmarkClient.put(body, "To", msg.getToAddress());
        PostmarkClient.put(body, "Cc", msg.getCc());
        PostmarkClient.put(body, "Bcc", msg.getBcc());
        PostmarkClient.put(body, "ReplyTo", msg.getReplyTo());
        PostmarkClient.put(body, "Tag", msg.getTag());
        PostmarkClient.put(body, "MessageStream", msg.getMessageStream());
    }

    private EmailMessage submit(EmailMessage msg, PostmarkClient.SendResult res) {
        if (res.ok()) {
            msg.setStatus(EmailStatus.SENT);
            msg.setPostmarkMessageId(res.messageId());
            msg.setErrorCode(0);
            msg.setSentAt(LocalDateTime.now());
        } else {
            msg.setStatus(EmailStatus.FAILED);
            msg.setErrorCode(res.errorCode());
            msg.setErrorMessage(res.message());
        }
        return repository.save(msg);
    }

    private static void requireText(String value, String message) {
        if (isBlank(value)) throw bad(message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
