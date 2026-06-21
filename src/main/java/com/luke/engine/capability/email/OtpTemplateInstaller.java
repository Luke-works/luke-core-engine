package com.luke.engine.capability.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Publishes the OTP verification email ({@link OtpEmailTemplate}) to the platform
 * Postmark server on startup so {@link EmailVerificationService} can send it by
 * alias. Idempotent — {@link PostmarkTemplateClient#upsert} creates the template or
 * updates the existing alias, so every boot re-syncs the latest copy.
 *
 * <p>OTP mail always goes out on the <em>fallback</em> server token (the tenant has
 * no server yet), so that is the only install target. Best-effort: if no fallback
 * token is configured, or Postmark rejects the publish, it logs and moves on — the
 * verification path falls back to an inline send, so a publish failure never blocks
 * OTP delivery.
 */
@Component
class OtpTemplateInstaller implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OtpTemplateInstaller.class);

    private final PostmarkTemplateClient templateClient;

    /** Platform/fallback Postmark server token — the server OTP mail is sent from. */
    @Value("${luke.email.postmark.server-token:}")
    private String platformToken;

    @Value("${luke.email.otp.template-alias:" + OtpEmailTemplate.DEFAULT_ALIAS + "}")
    private String alias;

    OtpTemplateInstaller(PostmarkTemplateClient templateClient) {
        this.templateClient = templateClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (platformToken == null || platformToken.isBlank()) {
            log.info("OTP template not published — no platform Postmark server token configured "
                    + "(set POSTMARK_SERVER_TOKEN). Verification will use the inline fallback.");
            return;
        }
        try {
            PostmarkTemplateClient.UpsertResult res = templateClient.upsert(
                    platformToken, alias, OtpEmailTemplate.NAME,
                    OtpEmailTemplate.SUBJECT, OtpEmailTemplate.HTML, OtpEmailTemplate.TEXT);
            log.info("Published OTP email template '{}' to Postmark (template id {})", res.alias(), res.templateId());
        } catch (RuntimeException e) {
            // Non-fatal: the verification path renders the same template inline if the
            // stored one is missing, so don't fail startup over a publish hiccup.
            log.warn("Could not publish OTP email template '{}' to Postmark: {} — "
                    + "verification will use the inline fallback.", alias, e.getMessage());
        }
    }
}
