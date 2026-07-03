package com.luke.engine.capability.email;

import java.util.Map;

/**
 * Inbound send request shared by the tenant-facing and internal controllers.
 * Carries both raw-email fields (subject/htmlBody/textBody) and template fields
 * (templateId|templateAlias + templateModel); the service picks the right Postmark
 * endpoint per call. All fields optional at the type level — required-ness is
 * enforced per send mode in {@link EmailService}.
 *
 * @param from          sender; falls back to the platform default when blank
 * @param to            comma-separated recipients (required)
 * @param cc            comma-separated cc
 * @param bcc           comma-separated bcc
 * @param replyTo       Reply-To header
 * @param subject       subject (raw sends; templates set it themselves)
 * @param htmlBody      HTML body (raw sends)
 * @param textBody      plain-text body (raw sends)
 * @param templateId    Postmark numeric template id (template sends)
 * @param templateAlias Postmark template alias (template sends)
 * @param templateModel substitution model for the template
 * @param tag           Postmark analytics tag
 * @param messageStream Postmark message stream (defaults to the configured stream)
 * @param metadata      Postmark Metadata (raw sends)
 * @param context       caller linkage stored on the audit row (process/form ids)
 */
public record EmailRequest(
        String from,
        String to,
        String cc,
        String bcc,
        String replyTo,
        String subject,
        String htmlBody,
        String textBody,
        Long templateId,
        String templateAlias,
        Map<String, Object> templateModel,
        String tag,
        String messageStream,
        Map<String, Object> metadata,
        Map<String, Object> context) {}
