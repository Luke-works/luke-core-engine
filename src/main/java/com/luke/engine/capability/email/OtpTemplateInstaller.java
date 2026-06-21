package com.luke.engine.capability.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the OTP verification email ({@link OtpEmailTemplate}) onto the platform
 * Postmark server on startup so {@link EmailVerificationService} can send it by
 * alias — but only when the alias is <em>missing</em>. Postmark is the source of
 * truth: an existing template is left untouched, so a hand-maintained design in the
 * Postmark dashboard is never overwritten by a deploy (see
 * {@link PostmarkTemplateClient#createIfAbsent}).
 *
 * <p>OTP mail always goes out on the <em>fallback</em> server token (the tenant has
 * no server yet), so that is the only seed target. Best-effort: if no fallback token
 * is configured, or Postmark rejects the create, it logs and moves on — the
 * verification path falls back to an inline send, so this never blocks OTP delivery.
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
            PostmarkTemplateClient.InstallResult res = templateClient.createIfAbsent(
                    platformToken, alias, OtpEmailTemplate.NAME,
                    OtpEmailTemplate.SUBJECT, OtpEmailTemplate.HTML, OtpEmailTemplate.TEXT);
            if (res.outcome() == PostmarkTemplateClient.InstallOutcome.CREATED) {
                log.info("Seeded OTP email template '{}' onto Postmark (template id {})", res.alias(), res.templateId());
            } else {
                log.info("OTP email template '{}' already exists on Postmark — leaving it as the "
                        + "source of truth (not overwriting).", res.alias());
            }
        } catch (RuntimeException e) {
            // Non-fatal: the verification path renders the same template inline if the
            // stored one is missing, so don't fail startup over a seeding hiccup.
            log.warn("Could not seed OTP email template '{}' onto Postmark: {} — "
                    + "verification will use the inline fallback.", alias, e.getMessage());
        }
    }
}
