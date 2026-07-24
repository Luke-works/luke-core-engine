package com.luke.engine.recipient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The SMS OTP channel SEAM. The fleet has no SMS gateway yet (the "phone" capability is Vapi VOICE,
 * not SMS), so this sender is present — so the SMS channel is wired end-to-end through
 * {@link PortalService} and the UI — but reports {@link #available()} = false until a gateway is
 * configured. Activating SMS is then a two-line change: set {@code luke.forms.sms.enabled=true} and
 * implement {@link #send} against the provider (e.g. Twilio). No portal/UI code changes needed.
 */
@Component
public class SmsPortalOtpSender implements PortalOtpSender {

    private final boolean enabled;

    public SmsPortalOtpSender(@Value("${luke.forms.sms.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public PortalChannel channel() {
        return PortalChannel.SMS;
    }

    @Override
    public boolean available() {
        return enabled;
    }

    @Override
    public String send(String tenantId, String contact, String code, String user) {
        // No provider wired. When one is added, gate on `enabled` and call it here.
        throw new UnsupportedOperationException("SMS delivery is not configured");
    }
}
