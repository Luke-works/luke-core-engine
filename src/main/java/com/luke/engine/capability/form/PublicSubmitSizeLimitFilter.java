package com.luke.engine.capability.form;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the body size of UNAUTHENTICATED form POSTs BEFORE they are deserialized (Route B M3). A
 * submission is form-field data; anything beyond {@link #MAX_BYTES} is rejected with 413 up front, so
 * a hostile public POST can't make the engine materialize a huge JSON document on the heap (the
 * in-validator caps only run after parsing). Chunked requests with no Content-Length fall through here
 * and remain bounded by the gateway's own request-size cap upstream.
 *
 * <p>Covers BOTH account-less form surfaces — the embed webhook and the OTP recipient portal. Was
 * embed-only; the portal is equally anonymous (an emailed link plus a one-time code is not a user),
 * so it needs the same cap.
 */
@Component
public class PublicSubmitSizeLimitFilter extends OncePerRequestFilter {

    /** 2 MiB — generous for form fields including a signature data-URL, tiny next to a parsing-DoS payload. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    /** The account-less POST surfaces. Anything under these prefixes is capped. */
    private static final String[] GUARDED_PREFIXES = {
        "/api/public/embed/",
        "/api/public/form-instances/",
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod())
                && isGuarded(request.getRequestURI())
                && request.getContentLengthLong() > MAX_BYTES) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Submission too large.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isGuarded(String uri) {
        if (uri == null) return false;
        for (String prefix : GUARDED_PREFIXES) {
            if (uri.startsWith(prefix)) return true;
        }
        return false;
    }
}
