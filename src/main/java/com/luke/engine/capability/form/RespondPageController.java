package com.luke.engine.capability.form;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the PUBLIC outbound RECIPIENT page HTML — a "different static serve" for prefilled forms,
 * mirroring {@link EmbedPageController} but for the per-instance fill surface. The page is a tiny
 * shell that boots the self-contained recipient bundle (vendored from consumer-ui at
 * {@code /respond-assets/respond.js}); the bundle reads the opaque instance token from the URL path
 * and calls the same-origin public API ({@code /api/public/form-instances/**}), which enforces the
 * OTP challenge + access token. Nothing sensitive is in the shell, so it is served for any token
 * (validity is decided by the API, not here).
 *
 * <p>Unlike the embed page this is a TOP-LEVEL page (never framed by a third party), so it ships
 * {@code frame-ancestors 'none'} to block clickjacking rather than a per-tenant allowlist.
 */
@RestController
public class RespondPageController {

    @GetMapping(value = "/respond/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.set("Content-Security-Policy", "frame-ancestors 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl(CacheControl.noCache().cachePrivate());
        return new ResponseEntity<>(SHELL, headers, HttpStatus.OK);
    }

    private static final String SHELL = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Form</title>
            <link rel="icon" href="data:,">
            <link rel="stylesheet" href="/respond-assets/respond.css">
            </head>
            <body>
            <div id="root"></div>
            <script type="module" src="/respond-assets/respond.js"></script>
            </body>
            </html>
            """;
}
