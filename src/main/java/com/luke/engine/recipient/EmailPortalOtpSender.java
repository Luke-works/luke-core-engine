package com.luke.engine.recipient;

import com.luke.engine.capability.email.EmailRequest;
import com.luke.engine.capability.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers a portal OTP by email via the tenant's configured {@link EmailService}. Always available.
 * Mirrors {@code PublicFormInstanceService.deliver} — best-effort, returns the send status, never
 * throws for a delivery failure (the code is stored regardless so verification still works).
 */
@Component
public class EmailPortalOtpSender implements PortalOtpSender {

    private static final Logger log = LoggerFactory.getLogger(EmailPortalOtpSender.class);

    private final EmailService emails;

    public EmailPortalOtpSender(EmailService emails) {
        this.emails = emails;
    }

    @Override
    public PortalChannel channel() {
        return PortalChannel.EMAIL;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String send(String tenantId, String contact, String code, String user) {
        try {
            String subject = "Your form portal code: " + code;
            String html = "<p>Your one-time code is:</p><p style=\"font-size:24px;font-weight:700;letter-spacing:3px\">"
                    + code + "</p><p>It expires in 10 minutes.</p>";
            String text = "Your one-time code is: " + code + "\nIt expires in 10 minutes.\n";
            EmailRequest req = new EmailRequest(
                    null, contact, null, null, null, subject, html, text,
                    null, null, null, "form-portal-otp", null, null, null);
            return String.valueOf(emails.sendRaw(tenantId, user, req).getStatus());
        } catch (RuntimeException e) {
            log.warn("Portal OTP email failed: {}", e.getMessage());
            return "FAILED";
        }
    }
}
