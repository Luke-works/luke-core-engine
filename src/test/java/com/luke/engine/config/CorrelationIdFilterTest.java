package com.luke.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** #27: per-request correlation id — accepted/generated, put in MDC, echoed on the response. */
class CorrelationIdFilterTest {

    @Test
    void sanitizeAcceptsSafeIdsAndGeneratesOtherwise() {
        assertEquals("abc-123_.X", CorrelationIdFilter.sanitize("abc-123_.X"));
        assertNotNull(CorrelationIdFilter.sanitize(null));
        assertNotEquals("", CorrelationIdFilter.sanitize(""));
        assertNotEquals("bad id!", CorrelationIdFilter.sanitize("bad id!")); // space/!
        assertNotEquals("x".repeat(65), CorrelationIdFilter.sanitize("x".repeat(65))); // too long
    }

    @Test
    void propagatesInboundIdToMdcAndResponseThenClears() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CorrelationIdFilter.HEADER, "trace-42");
        MockHttpServletResponse res = new MockHttpServletResponse();

        String[] seenInChain = new String[1];
        FilterChain chain = (rq, rs) -> seenInChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(req, res, chain);

        assertEquals("trace-42", seenInChain[0], "MDC carries the id during the request");
        assertEquals("trace-42", res.getHeader(CorrelationIdFilter.HEADER), "id echoed on the response");
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "MDC cleared after the request");
    }

    @Test
    void generatesIdWhenAbsent() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> {});

        String cid = res.getHeader(CorrelationIdFilter.HEADER);
        assertNotNull(cid);
        assertTrue(cid.length() >= 8, "a generated id is present");
    }
}
