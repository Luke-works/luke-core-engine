package com.luke.engine.capability.form;

import com.luke.engine.capability.email.EmailRequest;
import com.luke.engine.capability.email.EmailService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Outbound send (Phase 1): create a prefilled {@link FormInstance} for a named recipient and
 * email them a per-instance link. The link lands on the public, OTP-gated recipient surface
 * (consumer-ui {@code /respond/:token}); the recipient's email is preparer-asserted here and
 * is what the OTP challenge is later sent to (a recipient-supplied contact would be theater).
 *
 * <p>Delivery is best-effort: if the tenant has no email server configured (or Postmark
 * rejects), the instance + link are still returned so the preparer can share the link
 * manually, and {@code emailStatus} reflects the outcome. The instance starts in state SENT.
 */
@Service
public class OutboundSendService {

    private static final Logger log = LoggerFactory.getLogger(OutboundSendService.class);

    private final FormDefinitionRepository forms;
    private final FormInstanceRepository instances;
    private final EmailService emails;
    private final FormEventPublisher events;
    private final String recipientBaseUrl;

    public OutboundSendService(FormDefinitionRepository forms, FormInstanceRepository instances,
            EmailService emails, FormEventPublisher events,
            @Value("${luke.forms.recipient-base-url:http://localhost:5173}") String recipientBaseUrl) {
        this.forms = forms;
        this.instances = instances;
        this.emails = emails;
        this.events = events;
        this.recipientBaseUrl = stripTrailingSlash(recipientBaseUrl);
    }

    /** The outcome of a send: the created instance, its opaque token, the recipient link, and how the email went. */
    public record SendResult(String instanceId, String token, String link, String emailStatus) {}

    @Transactional
    public SendResult send(String tenantId, String formId, Map<String, Object> recipient,
            Map<String, Object> prefill, Long expiresAt, String user) {
        FormDefinition form = forms.findByIdAndTenantId(formId, tenantId)
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found"));
        if (!FormDefinition.KIND_OUTBOUND.equals(form.getKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only outbound forms can be sent to a recipient");
        }
        if (form.getPublishedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Publish the form before sending it");
        }
        String email = recipient == null ? null : str(recipient.get("email"));
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recipient.email is required");
        }

        FormInstance inst = new FormInstance();
        inst.setTenantId(tenantId);
        inst.setToken(uniqueToken());
        inst.setDefinitionCode(form.getCode());
        inst.setVersion(form.getPublishedVersion());
        inst.setState(FormInstanceStates.SENT);
        inst.setPrefill(prefill);
        inst.setRecipient(recipient);
        inst.setCreatedBy(user);
        if (expiresAt != null) inst.setExpiresAt(Instant.ofEpochMilli(expiresAt).atZone(ZoneId.systemDefault()).toLocalDateTime());
        instances.save(inst);
        events.emit(inst, "sent"); // forms→workflow "form sent" initiator

        String link = recipientBaseUrl + "/respond/" + inst.getToken();
        String emailStatus = deliver(tenantId, user, form, recipient, email.trim(), link);
        return new SendResult(inst.getId(), inst.getToken(), link, emailStatus);
    }

    /** Best-effort email; never fails the send (the link is returned regardless). */
    private String deliver(String tenantId, String user, FormDefinition form,
            Map<String, Object> recipient, String email, String link) {
        try {
            String first = recipient == null ? null : str(recipient.get("firstName"));
            String greeting = first == null || first.isBlank() ? "Hi there," : "Hi " + esc(first) + ",";
            String formName = esc(form.getName());
            String subject = "Please complete: " + form.getName();
            String html = "<p>" + greeting + "</p>"
                    + "<p>You've been asked to complete <strong>" + formName + "</strong>.</p>"
                    + "<p><a href=\"" + esc(link) + "\">Open the form</a></p>"
                    + "<p>To continue you'll confirm your email with a one-time code.</p>";
            String text = (first == null || first.isBlank() ? "Hi there," : "Hi " + first + ",")
                    + "\n\nYou've been asked to complete " + form.getName() + ".\n\nOpen the form: " + link
                    + "\n\nTo continue you'll confirm your email with a one-time code.\n";
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("formCode", form.getCode());
            EmailRequest req = new EmailRequest(
                    null, email, null, null, null, subject, html, text,
                    null, null, null, "form-outbound", null, null, ctx);
            return String.valueOf(emails.sendRaw(tenantId, user, req).getStatus());
        } catch (RuntimeException e) {
            log.warn("Outbound email delivery failed for {} (form {}): {}", email, form.getCode(), e.getMessage());
            return "FAILED";
        }
    }

    private String uniqueToken() {
        String token = FormSupport.generateToken();
        while (instances.existsByToken(token)) token = FormSupport.generateToken();
        return token;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
