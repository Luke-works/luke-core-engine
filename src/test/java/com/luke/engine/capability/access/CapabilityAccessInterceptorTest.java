package com.luke.engine.capability.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.access.CapabilityLevel.Action;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * #104: the interceptor resolves the required {@link Action} from a handler's
 * {@link RequiresCapabilityAction} (falling back to method-derived READ/WRITE) and enforces it —
 * so a {@code contributor} is blocked from a publish/delete route while still able to edit, and a
 * {@code read-write} holder is unaffected.
 */
class CapabilityAccessInterceptorTest {

    private final CapabilityAccessService access = mock(CapabilityAccessService.class);
    private final CapabilityAccessInterceptor interceptor =
            new CapabilityAccessInterceptor(access).forCapability("FORMS");

    /** Handlers used to build HandlerMethods with/without the annotation. */
    static class Handlers {
        @RequiresCapabilityAction(Action.PUBLISH)
        public void publish() {}

        @RequiresCapabilityAction(Action.DELETE)
        public void purge() {}

        public void edit() {}
    }

    private static HandlerMethod handler(String method) throws Exception {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(method));
    }

    private MockHttpServletRequest req(String httpMethod) {
        MockHttpServletRequest r = new MockHttpServletRequest(httpMethod, "/api/form-definitions/x");
        r.addHeader("X-Tenant-Id", "t");
        r.addHeader("X-User-Id", "u");
        return r;
    }

    @Test
    void annotatedPublishRouteRequiresThePublishAction() throws Exception {
        when(access.permits("t", "u", "FORMS", Action.PUBLISH)).thenReturn(true);
        boolean allowed = interceptor.preHandle(req("POST"), new MockHttpServletResponse(), handler("publish"));
        assertThat(allowed).isTrue();
        verify(access).permits("t", "u", "FORMS", Action.PUBLISH); // resolved action, not plain WRITE
    }

    @Test
    void annotatedPurgeRouteRequiresTheDeleteAction() throws Exception {
        when(access.permits("t", "u", "FORMS", Action.DELETE)).thenReturn(true);
        interceptor.preHandle(req("DELETE"), new MockHttpServletResponse(), handler("purge"));
        verify(access).permits("t", "u", "FORMS", Action.DELETE);
    }

    @Test
    void unannotatedRouteFallsBackToMethodDerivedReadWrite() throws Exception {
        when(access.permits(eq("t"), eq("u"), eq("FORMS"), org.mockito.ArgumentMatchers.any())).thenReturn(true);

        interceptor.preHandle(req("GET"), new MockHttpServletResponse(), handler("edit"));
        verify(access).permits("t", "u", "FORMS", Action.READ);

        interceptor.preHandle(req("POST"), new MockHttpServletResponse(), handler("edit"));
        verify(access).permits("t", "u", "FORMS", Action.WRITE);
    }

    @Test
    void contributorIsForbiddenFromPublishWith403() throws Exception {
        when(access.permits("t", "u", "FORMS", Action.PUBLISH)).thenReturn(false);
        when(access.effectiveLevel("t", "u", "FORMS")).thenReturn("contributor");

        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(req("POST"), res, handler("publish"));

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(res.getContentAsString()).contains("publish");
    }

    @Test
    void missingIdentityIs401() throws Exception {
        MockHttpServletRequest noHeaders = new MockHttpServletRequest("POST", "/api/form-definitions/x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(noHeaders, res, handler("publish"));
        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
