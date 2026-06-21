package com.luke.engine.capability.email;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single source of truth for the one-time-passcode (OTP) verification email.
 *
 * <p>The same subject/HTML/text carry Postmark {@code {{var}}} merge fields and are
 * used two ways: published to Postmark as a stored template (see
 * {@link OtpTemplateInstaller}) so a normal template-send fills them, and rendered
 * in-process by {@link #render} for the inline fallback when the stored template
 * isn't available. Merge fields: {@code code}, {@code org_name},
 * {@code expiry_minutes}, {@code product_name}.
 */
final class OtpEmailTemplate {

    /** Default Postmark template alias; overridable via {@code luke.email.otp.template-alias}. */
    static final String DEFAULT_ALIAS = "luke-otp";

    /** Human name shown in the Postmark dashboard. */
    static final String NAME = "One-time passcode";

    static final String SUBJECT = "Your {{product_name}} verification code: {{code}}";

    static final String HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>{{product_name}} verification code</title>
            </head>
            <body style="margin:0;padding:0;background-color:#f4f5f7;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f4f5f7;padding:24px 0;">
                <tr>
                  <td align="center">
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background-color:#ffffff;border-radius:12px;overflow:hidden;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                      <tr>
                        <td style="background-color:#4f46e5;padding:20px 32px;">
                          <span style="color:#ffffff;font-size:18px;font-weight:600;letter-spacing:0.2px;">{{product_name}}</span>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:32px 32px 8px 32px;">
                          <h1 style="margin:0;font-size:20px;color:#111827;font-weight:600;">Verify your email</h1>
                          <p style="margin:12px 0 0 0;font-size:14px;line-height:22px;color:#4b5563;">
                            Use the code below to verify <strong>{{org_name}}</strong> and finish connecting your email.
                          </p>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:24px 32px;">
                          <div style="background-color:#f3f4f6;border-radius:10px;padding:18px;text-align:center;">
                            <span style="font-size:32px;font-weight:700;letter-spacing:8px;color:#111827;font-family:'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace;">{{code}}</span>
                          </div>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:0 32px 8px 32px;">
                          <p style="margin:0;font-size:13px;line-height:20px;color:#6b7280;">
                            This code expires in {{expiry_minutes}} minutes. If you didn't request it, you can safely ignore this email.
                          </p>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:24px 32px 32px 32px;border-top:1px solid #f0f0f0;">
                          <p style="margin:0;font-size:12px;line-height:18px;color:#9ca3af;">
                            Sent by {{product_name}}. Please don't reply to this message.
                          </p>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """;

    static final String TEXT = """
            {{product_name}} — verify your email

            Use this code to verify {{org_name}} and finish connecting your email:

                {{code}}

            This code expires in {{expiry_minutes}} minutes.
            If you didn't request it, you can safely ignore this email.
            """;

    /** Any {@code {{identifier}}} occurrence (Postmark/Mustachio merge key). */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");

    private OtpEmailTemplate() {}

    /**
     * Render {@code template} by substituting {@code {{var}}} merge fields from
     * {@code model}; unknown fields become empty. When {@code escapeHtml} is true,
     * values are HTML-escaped (use for the HTML body; not for subject/text). This
     * mirrors how Postmark's Mustachio fills a stored template's {@code {{var}}}.
     */
    static String render(String template, Map<String, String> model, boolean escapeHtml) {
        if (template == null) return null;
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            String value = model.getOrDefault(m.group(1), "");
            out.append(escapeHtml ? escapeHtml(value) : value);
            last = m.end();
        }
        out.append(template.substring(last));
        return out.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
