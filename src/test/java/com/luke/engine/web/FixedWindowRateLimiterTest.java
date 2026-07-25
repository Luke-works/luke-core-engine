package com.luke.engine.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

/** #55: the shared fixed-window limiter allows up to max/min, then returns a Retry-After / 429. */
class FixedWindowRateLimiterTest {

    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();

    @Test
    void allowsUpToMaxThenReportsRetryAfter() {
        assertEquals(-1, limiter.retryAfterSeconds("k", 2));
        assertEquals(-1, limiter.retryAfterSeconds("k", 2));
        long retryAfter = limiter.retryAfterSeconds("k", 2); // 3rd in the minute
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After within the window: " + retryAfter);
    }

    @Test
    void differentKeysAreIndependent() {
        assertEquals(-1, limiter.retryAfterSeconds("a", 1));
        assertEquals(-1, limiter.retryAfterSeconds("b", 1)); // its own bucket
        assertTrue(limiter.retryAfterSeconds("a", 1) >= 1);  // a is now over
    }

    @Test
    void enforceSetsRetryAfterHeaderAndThrows429() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        limiter.enforce("k", 1, res); // 1st allowed
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> limiter.enforce("k", 1, res)); // 2nd over
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertTrue(res.getHeader("Retry-After") != null, "Retry-After header set");
        assertTrue(Integer.parseInt(res.getHeader("Retry-After")) >= 1);
    }
}
