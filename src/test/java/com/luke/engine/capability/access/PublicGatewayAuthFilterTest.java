package com.luke.engine.capability.access;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * PublicGatewayAuthFilter locks the public surface to gateway-only ONLY when a secret is configured,
 * and is OPEN (pass-through) when it isn't — so dev/qa keep serving embed traffic with zero config
 * (the deliberate inverse of the fail-closed InternalAuthFilter).
 */
class PublicGatewayAuthFilterTest {

    private final FilterChain chain = mock(FilterChain.class);

    private HttpServletRequest req(String method, String gatewayAuth) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getMethod()).thenReturn(method);
        when(r.getHeader("X-Gateway-Auth")).thenReturn(gatewayAuth);
        return r;
    }

    private HttpServletResponse res() {
        HttpServletResponse r = mock(HttpServletResponse.class);
        try {
            when(r.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return r;
    }

    @Test
    void unsetSecretIsOpenAndPassesThroughWithoutAnyHeader() throws Exception {
        HttpServletResponse res = res();
        new PublicGatewayAuthFilter.Impl("").doFilter(req("GET", null), res, chain);
        verify(chain).doFilter(any(), any());          // default-lenient: dev/qa serve embed unconfigured
        verify(res, never()).setStatus(anyInt());
    }

    @Test
    void configuredWithValidVouchPassesThrough() throws Exception {
        new PublicGatewayAuthFilter.Impl("s3cret").doFilter(req("GET", "s3cret"), res(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void configuredWithMissingVouchIsRejectedAndNeverProceeds() throws Exception {
        HttpServletResponse res = res();
        new PublicGatewayAuthFilter.Impl("s3cret").doFilter(req("GET", null), res, chain);
        verify(res).setStatus(404);                    // direct-to-core hit blocked
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void configuredWithWrongVouchIsRejected() throws Exception {
        HttpServletResponse res = res();
        new PublicGatewayAuthFilter.Impl("s3cret").doFilter(req("GET", "guess"), res, chain);
        verify(res).setStatus(404);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void configuredStillLetsCorsPreflightThrough() throws Exception {
        new PublicGatewayAuthFilter.Impl("s3cret").doFilter(req("OPTIONS", null), res(), chain);
        verify(chain).doFilter(any(), any());          // OPTIONS must not be blocked
    }
}
