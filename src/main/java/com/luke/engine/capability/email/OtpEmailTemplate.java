package com.luke.engine.capability.email;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The in-code copy of the one-time-passcode (OTP) verification email.
 *
 * <p>Postmark is the source of truth for the stored {@code luke-otp} template; this
 * copy is used two ways: seeded to Postmark only when the alias is <em>missing</em>
 * (see {@link OtpTemplateInstaller} — it never overwrites an existing template), and
 * rendered in-process by {@link #render} for the inline fallback when a template-send
 * can't be used. Keep this in sync with the Postmark-hosted template. Merge fields:
 * {@code code}, {@code org_name}, {@code expiry_minutes}, {@code product_name}.
 */
final class OtpEmailTemplate {

    /** Default Postmark template alias; overridable via {@code luke.email.otp.template-alias}. */
    static final String DEFAULT_ALIAS = "luke-otp";

    /** Human name shown in the Postmark dashboard. */
    static final String NAME = "One-time passcode";

    static final String SUBJECT = "{{code}} is your {{product_name}} verification code";

    static final String HTML = """
            <!DOCTYPE html>
            <html lang="en" xmlns="http://www.w3.org/1999/xhtml">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <meta http-equiv="X-UA-Compatible" content="IE=edge">
              <meta name="color-scheme" content="light dark">
              <meta name="supported-color-schemes" content="light dark">
              <title>{{product_name}} verification code</title>
              <style>
                /* Critical styles are inline for client safety; these are progressive niceties. */
                @media only screen and (max-width:600px){
                  .container{width:100% !important;}
                  .px{padding-left:24px !important;padding-right:24px !important;}
                  .code{font-size:32px !important;letter-spacing:10px !important;}
                }
                @media (prefers-color-scheme: dark){
                  .bg{background:#0d0f13 !important;}
                  .card{background:#15181e !important;border-color:#262b34 !important;}
                  .h1{color:#f5f7fa !important;}
                  .text{color:#c7cdd8 !important;}
                  .muted{color:#9aa3b2 !important;}
                  .panel{background:#0d0f13 !important;border-color:#2a3039 !important;}
                  .code{color:#f5f7fa !important;}
                  .rule{background:#262b34 !important;}
                }
                a{text-decoration:none;}
              </style>
            </head>
            <body class="bg" style="margin:0;padding:0;width:100%;background-color:#f3f5f8;-webkit-font-smoothing:antialiased;-webkit-text-size-adjust:100%;">
              <div style="display:none;max-height:0;overflow:hidden;mso-hide:all;opacity:0;color:transparent;height:0;width:0;line-height:0;">
                Your {{product_name}} verification code is {{code}}. It expires in {{expiry_minutes}} minutes.
              </div>
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" class="bg" style="background-color:#f3f5f8;">
                <tr>
                  <td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="600" cellpadding="0" cellspacing="0" class="container card" style="width:600px;max-width:600px;background-color:#ffffff;border:1px solid #e6e9ee;border-radius:16px;overflow:hidden;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">

                      <tr>
                        <td class="px" style="padding:30px 44px 0 44px;">
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
                            <tr>
                              <td align="left" style="vertical-align:middle;">
                                <span style="font-size:19px;font-weight:700;color:#4f46e5;letter-spacing:-0.3px;">{{product_name}}</span>
                              </td>
                              <td align="right" style="vertical-align:middle;">
                                <span class="muted" style="font-size:11px;font-weight:700;color:#98a2b3;text-transform:uppercase;letter-spacing:1.2px;">Account security</span>
                              </td>
                            </tr>
                          </table>
                          <div class="rule" style="height:1px;line-height:1px;font-size:0;background-color:#edf0f4;margin-top:22px;">&nbsp;</div>
                        </td>
                      </tr>

                      <tr>
                        <td class="px" style="padding:34px 44px 0 44px;">
                          <h1 class="h1" style="margin:0;font-size:23px;line-height:31px;font-weight:700;color:#0f172a;letter-spacing:-0.4px;">Verify your email address</h1>
                          <p class="text" style="margin:14px 0 0 0;font-size:15px;line-height:25px;color:#475467;">
                            Use the verification code below to confirm <strong style="color:#0f172a;">{{org_name}}</strong> and finish connecting your email. This keeps your account secure.
                          </p>
                        </td>
                      </tr>

                      <tr>
                        <td class="px" style="padding:30px 44px 0 44px;">
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" class="panel" style="background-color:#f8fafc;border:1px solid #e9edf2;border-radius:14px;">
                            <tr>
                              <td align="center" style="padding:30px 20px;">
                                <div class="muted" style="font-size:11px;font-weight:700;color:#667085;text-transform:uppercase;letter-spacing:1.4px;margin-bottom:14px;">Verification code</div>
                                <div class="code" style="font-size:40px;line-height:46px;font-weight:700;color:#0f172a;letter-spacing:14px;font-family:'SFMono-Regular',ui-monospace,SFMono,Consolas,'Liberation Mono',Menlo,monospace;">{{code}}</div>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>

                      <tr>
                        <td class="px" style="padding:20px 44px 0 44px;">
                          <p class="muted" style="margin:0;font-size:13px;line-height:21px;color:#667085;">
                            This code expires in <strong style="color:#475467;">{{expiry_minutes}} minutes</strong>. For your security, never share it with anyone — {{product_name}} will never ask you for it.
                          </p>
                        </td>
                      </tr>

                      <tr>
                        <td class="px" style="padding:30px 44px 0 44px;">
                          <div class="rule" style="height:1px;line-height:1px;font-size:0;background-color:#edf0f4;">&nbsp;</div>
                        </td>
                      </tr>

                      <tr>
                        <td class="px" style="padding:20px 44px 0 44px;">
                          <p class="muted" style="margin:0;font-size:13px;line-height:21px;color:#667085;">
                            Didn't request this code? You can safely ignore this email, or contact your administrator if you believe something is wrong.
                          </p>
                        </td>
                      </tr>

                      <tr>
                        <td class="px" style="padding:30px 44px 34px 44px;">
                          <p class="muted" style="margin:0;font-size:12px;line-height:18px;color:#98a2b3;">
                            This is an automated security message from {{product_name}}. Please do not reply to this email.
                          </p>
                          <p class="muted" style="margin:8px 0 0 0;font-size:12px;line-height:18px;color:#98a2b3;">
                            &copy; {{product_name}}. All rights reserved.
                          </p>
                        </td>
                      </tr>

                    </table>

                    <table role="presentation" width="600" cellpadding="0" cellspacing="0" class="container" style="width:600px;max-width:600px;">
                      <tr>
                        <td align="center" style="padding:22px 44px 0 44px;">
                          <p class="muted" style="margin:0;font-size:11px;line-height:16px;color:#aab1bd;">
                            {{product_name}} &bull; Sent to verify {{org_name}}
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
            {{product_name}} — verify your email address

            Use this verification code to confirm {{org_name}} and finish connecting your email:

                {{code}}

            This code expires in {{expiry_minutes}} minutes. For your security, never share it
            with anyone — {{product_name}} will never ask you for it.

            Didn't request this code? You can safely ignore this email, or contact your
            administrator if you believe something is wrong.

            This is an automated security message from {{product_name}}. Please do not reply.
            © {{product_name}}. All rights reserved.
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
