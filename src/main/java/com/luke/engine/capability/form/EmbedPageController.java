package com.luke.engine.capability.form;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the PUBLIC embed page HTML (Route B M2) — the document that loads inside a third-party
 * site's iframe. This is the one place that can attach a PER-TENANT clickjacking policy, because it
 * has both the embed-token secret (via {@link EmbedTokens}) and the form's allowlist: the response
 * carries {@code Content-Security-Policy: frame-ancestors <the form's allowed origins>}, which the
 * browser enforces as the authoritative control over who may frame the form.
 *
 * <p>The page is a tiny shell that boots the self-contained embed renderer bundle (vendored from
 * consumer-ui at {@code /embed-assets/embed.js}); the bundle reads the token from the URL path and
 * calls the same-origin public API. Token signature is verified up front so we never serve a shell
 * (nor compute a policy) for a forged token.
 */
@RestController
public class EmbedPageController {

    private final EmbedTokens embedTokens;
    private final FormDefinitionRepository forms;
    private final FormEmbedSiteRecorder embedSites;

    public EmbedPageController(EmbedTokens embedTokens, FormDefinitionRepository forms,
                               FormEmbedSiteRecorder embedSites) {
        this.embedTokens = embedTokens;
        this.forms = forms;
        this.embedSites = embedSites;
    }

    @GetMapping(value = "/embed/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token,
                                       @RequestHeader(value = "Referer", required = false) String referer,
                                       @RequestHeader(value = "Sec-Fetch-Dest", required = false) String secFetchDest) {
        EmbedTokens.EmbedRef ref;
        try {
            ref = embedTokens.verify(token);
        } catch (IllegalArgumentException e) {
            return notFound();
        }
        FormDefinition form = forms.findByTenantIdAndCode(ref.tenantId(), ref.code())
                .filter(f -> f.getDeletedAt() == null)
                .orElse(null);
        if (form == null) return notFound();
        if (ref.keyVersion() != form.getEmbedKeyVersion()) return notFound(); // revoked token (M4)

        // Note where this form is live, for the author's "Embedded on" list. This is the ONE request that
        // can see it: the iframe's DOCUMENT request carries the embedding page as its Referer, while the
        // XHRs the bundle makes afterwards carry this page's own URL. Observation only — it never gates
        // anything, and it can't fail the response (see FormEmbedSiteRecorder).
        embedSites.record(ref.tenantId(), form.getCode(), referer, secFetchDest);

        // The authoritative clickjacking control: only these origins may frame this form (empty
        // allowlist → "*", the public default). Junk in the allowlist was already dropped at write time.
        String frameAncestors = FrameAncestors.directive(form.getAllowedEmbedOrigins());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.set("Content-Security-Policy","frame-ancestors " + frameAncestors);
        headers.set("X-Content-Type-Options", "nosniff");
        // The policy is per-token; don't let a shared cache serve one form's header for another.
        headers.setCacheControl(CacheControl.noCache().cachePrivate());
        return new ResponseEntity<>(SHELL, headers, HttpStatus.OK);
    }

    private ResponseEntity<String> notFound() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        // A forged/stale link must not be framable as if it were a real form.
        headers.set("Content-Security-Policy","frame-ancestors 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        return new ResponseEntity<>(NOT_FOUND_HTML, headers, HttpStatus.NOT_FOUND);
    }

    // The shell boots the self-contained renderer bundle, which reads the token from the path
    // (/embed/{token}) and calls the same-origin public API. No token is interpolated here, so there
    // is no injection surface in the HTML.
    private static final String SHELL = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Form</title>
            <!-- Suppress the browser's default /favicon.ico request: that origin is the auth gateway,
                 which 401s non-allowlisted paths (and an iframe shows no favicon anyway). -->
            <link rel="icon" href="data:,">
            <link rel="stylesheet" href="/embed-assets/embed.css">
            </head>
            <body>
            <div id="root"></div>
            <script type="module" src="/embed-assets/embed.js"></script>
            </body>
            </html>
            """;

    private static final String NOT_FOUND_HTML = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="UTF-8"><title>Form unavailable</title><link rel="icon" href="data:,"></head>
            <body style="font:14px system-ui;padding:2rem;text-align:center;color:#64748b">
            This form link is invalid or no longer available.
            </body></html>
            """;
}
