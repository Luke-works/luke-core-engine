package com.luke.engine.capability.email;

import com.luke.engine.capability.email.EmailServerService.SendContext;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates a send: resolve the tenant's send context (which Postmark Server token to use + the
 * verified company sender) → validate → persist a QUEUED audit row → hand delivery to
 * {@link EmailDispatcher}. The row is always saved, so a delivery failure is recorded (not thrown)
 * and the {@link EmailMessage} status tells the story.
 *
 * <p><b>Async by default (#59):</b> {@link #sendRaw}/{@link #sendTemplate} persist the QUEUED row and
 * return immediately — the API is not blocked on Postmark — while {@code EmailDispatcher} delivers
 * off-thread after commit, with retry. For callers that need the terminal outcome inline (recipient
 * OTP), {@link #sendRawSync}/{@link #sendTemplateSync} deliver synchronously (Postmark has its own
 * connect/read timeouts, so the blocking window is bounded).
 *
 * <p>Sender ownership is enforced by {@link EmailServerService}: a company may only send from its own
 * domain. Caller mistakes (missing recipient/body/template id, or a foreign sender) throw before
 * anything is queued.
 */
@Service
public class EmailService {

    private final EmailMessageRepository repository;
    private final EmailServerService servers;
    private final ApplicationEventPublisher events;
    private final EmailDispatcher dispatcher;

    public EmailService(EmailMessageRepository repository, EmailServerService servers,
                        ApplicationEventPublisher events, EmailDispatcher dispatcher) {
        this.repository = repository;
        this.servers = servers;
        this.events = events;
        this.dispatcher = dispatcher;
    }

    /** Queue a raw HTML/text email; returns the persisted QUEUED row (delivered async, with retry). */
    public EmailMessage sendRaw(String tenantId, String createdBy, EmailRequest req) {
        return queue(prepareRaw(tenantId, createdBy, req));
    }

    /** Queue a stored-template email; returns the persisted QUEUED row (delivered async, with retry). */
    public EmailMessage sendTemplate(String tenantId, String createdBy, EmailRequest req) {
        return queue(prepareTemplate(tenantId, createdBy, req));
    }

    /** Send a raw email SYNCHRONOUSLY and return the terminal (SENT/FAILED) row — for OTP-style callers
     *  that must confirm delivery inline. */
    public EmailMessage sendRawSync(String tenantId, String createdBy, EmailRequest req) {
        return deliver(prepareRaw(tenantId, createdBy, req));
    }

    /** Send a stored-template email SYNCHRONOUSLY and return the terminal row. */
    public EmailMessage sendTemplateSync(String tenantId, String createdBy, EmailRequest req) {
        return deliver(prepareTemplate(tenantId, createdBy, req));
    }

    /* ── prepare + dispatch ─────────────────────────────────────────── */

    /** A persisted QUEUED row plus everything needed to deliver it. */
    private record Prepared(EmailMessage msg, String serverToken, Map<String, Object> body, boolean template) {}

    private EmailMessage queue(Prepared p) {
        events.publishEvent(new EmailQueuedEvent(p.msg().getId(), p.serverToken(), p.body(), p.template()));
        return p.msg();
    }

    private EmailMessage deliver(Prepared p) {
        return dispatcher.deliver(p.msg(), p.serverToken(), p.body(), p.template());
    }

    private Prepared prepareRaw(String tenantId, String createdBy, EmailRequest req) {
        requireText(req.to(), "to is required");
        if (isBlank(req.subject())) throw bad("subject is required");
        if (isBlank(req.htmlBody()) && isBlank(req.textBody())) {
            throw bad("htmlBody or textBody is required");
        }
        SendContext ctx = servers.resolveSendContext(tenantId, req.from());

        EmailMessage msg = newRow(tenantId, createdBy, req, ctx);
        msg.setSubject(req.subject());
        msg.setModel(req.metadata());
        repository.save(msg);

        Map<String, Object> body = PostmarkClient.body();
        applyCommon(body, msg);
        PostmarkClient.put(body, "Subject", req.subject());
        PostmarkClient.put(body, "HtmlBody", req.htmlBody());
        PostmarkClient.put(body, "TextBody", req.textBody());
        PostmarkClient.put(body, "Metadata", req.metadata());
        return new Prepared(msg, ctx.serverToken(), body, false);
    }

    private Prepared prepareTemplate(String tenantId, String createdBy, EmailRequest req) {
        requireText(req.to(), "to is required");
        if (req.templateId() == null && isBlank(req.templateAlias())) {
            throw bad("templateId or templateAlias is required");
        }
        SendContext ctx = servers.resolveSendContext(tenantId, req.from());

        EmailMessage msg = newRow(tenantId, createdBy, req, ctx);
        msg.setTemplateId(req.templateId());
        msg.setTemplateAlias(req.templateAlias());
        msg.setModel(req.templateModel());
        repository.save(msg);

        Map<String, Object> body = PostmarkClient.body();
        applyCommon(body, msg);
        PostmarkClient.put(body, "TemplateId", req.templateId());
        PostmarkClient.put(body, "TemplateAlias", req.templateAlias());
        // TemplateModel must always be present (Postmark requires the key, even if empty).
        body.put("TemplateModel", req.templateModel() != null ? req.templateModel() : Map.of());
        return new Prepared(msg, ctx.serverToken(), body, true);
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
        return msg;
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
