package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

/** The public-submit body cap rejects oversized POSTs before parsing, on EVERY account-less surface. */
class PublicSubmitSizeLimitFilterTest {

    private final PublicSubmitSizeLimitFilter filter = new PublicSubmitSizeLimitFilter();

    private HttpServletRequest post(String uri, long contentLength) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getMethod()).thenReturn("POST");
        when(r.getRequestURI()).thenReturn(uri);
        when(r.getContentLengthLong()).thenReturn(contentLength);
        return r;
    }

    private void assertRejected(String uri) throws Exception {
        HttpServletRequest req = post(uri, PublicSubmitSizeLimitFilter.MAX_BYTES + 1);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(req, res, chain);
        verify(res).sendError(org.mockito.ArgumentMatchers.eq(413), org.mockito.ArgumentMatchers.anyString());
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void rejectsOversizedEmbedSubmissionWith413BeforeChain() throws Exception {
        assertRejected("/api/public/embed/tok/submit");
    }

    @Test
    void rejectsOversizedRecipientPortalSubmissionWith413BeforeChain() throws Exception {
        // The OTP portal is equally account-less — an emailed link plus a code is not a user — so it
        // gets the same pre-parse cap the embed door has always had.
        assertRejected("/api/public/form-instances/tok/submit");
    }

    @Test
    void letsNormalSubmissionThrough() throws Exception {
        HttpServletRequest req = post("/api/public/embed/tok/submit", 4096);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void ignoresNonPublicAndChunked() throws Exception {
        // chunked (no Content-Length = -1) on a guarded path → passes (bounded upstream by the gateway)
        HttpServletRequest chunked = post("/api/public/embed/tok/submit", -1L);
        FilterChain c1 = mock(FilterChain.class);
        filter.doFilterInternal(chunked, mock(HttpServletResponse.class), c1);
        verify(c1).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        // an authenticated (non-public) path with a huge body is not this filter's concern
        HttpServletRequest other = post("/api/forms/x", PublicSubmitSizeLimitFilter.MAX_BYTES * 10);
        FilterChain c2 = mock(FilterChain.class);
        filter.doFilterInternal(other, mock(HttpServletResponse.class), c2);
        verify(c2).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(true).isTrue();
    }
}
