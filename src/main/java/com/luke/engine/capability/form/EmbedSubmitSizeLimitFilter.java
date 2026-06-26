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
 * Caps the body size of UNAUTHENTICATED embed submissions BEFORE they are deserialized (Route B M3).
 * A submission is form-field data; anything beyond {@link #MAX_BYTES} is rejected with 413 up front, so
 * a hostile public POST can't make the engine materialize a huge JSON document on the heap (the
 * in-validator caps only run after parsing). Chunked requests with no Content-Length fall through here
 * and remain bounded by the gateway's own request-size cap upstream.
 */
@Component
public class EmbedSubmitSizeLimitFilter extends OncePerRequestFilter {

    /** 2 MiB — generous for form fields including a signature data-URL, tiny next to a parsing-DoS payload. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if ("POST".equalsIgnoreCase(request.getMethod())
                && uri != null
                && uri.startsWith("/api/public/embed/")
                && request.getContentLengthLong() > MAX_BYTES) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Submission too large.");
            return;
        }
        chain.doFilter(request, response);
    }
}
