package com.luke.engine.recipient;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the PUBLIC recipient PORTAL page HTML — a tiny shell that boots the self-contained portal
 * bundle (vendored from consumer-ui at {@code /portal-assets/portal.js}). The bundle reads the signed
 * per-tenant portal handle from the URL path ({@code /portal/{token}}) and the optional {@code ?lt=}
 * magic-link token from the query, then calls the same-origin public API ({@code /api/public/portal/**}),
 * which enforces the OTP / magic-link challenge + email-scoped session. Nothing sensitive is in the
 * shell, so it is served for any handle (validity is decided by the API, not here).
 *
 * <p>Like {@link RespondPageController} this is a TOP-LEVEL page (never framed), so it ships
 * {@code frame-ancestors 'none'} to block clickjacking.
 */
@RestController
public class PortalPageController {

    @GetMapping(value = "/portal/{token}", produces = MediaType.TEXT_HTML_VALUE)
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
            <title>My Forms</title>
            <link rel="icon" href="data:,">
            <link rel="stylesheet" href="/portal-assets/portal.css">
            </head>
            <body>
            <div id="root"></div>
            <script type="module" src="/portal-assets/portal.js"></script>
            </body>
            </html>
            """;
}
