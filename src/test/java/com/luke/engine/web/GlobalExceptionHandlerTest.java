package com.luke.engine.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luke.engine.config.CorrelationIdFilter;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * #63 — one consistent error body ({error, message, status, correlationId}); a developer-authored
 * {@code ResponseStatusException} reason passes through; an unexpected exception is 500 with a
 * generic message and leaks nothing.
 */
class GlobalExceptionHandlerTest {

    private static final String SECRET_DETAIL = "SecretCrypto key v1 at /etc/keys/prod.pem missing";

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Boom())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @RestController
    static class Boom {
        @GetMapping("/boom/badrequest")
        String badRequest() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tenant 'acme'");
        }

        @GetMapping("/boom/unexpected")
        String unexpected() {
            throw new IllegalStateException(SECRET_DETAIL); // e.g. SecretCrypto / JsonMapConverter
        }
    }

    @Test
    void responseStatusExceptionKeepsItsStatusAndReason() throws Exception {
        mvc.perform(get("/boom/badrequest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Unknown tenant 'acme'"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unexpectedExceptionIs500AndLeaksNothing() throws Exception {
        mvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(content().string(Matchers.not(Matchers.containsString("SecretCrypto"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/etc/keys"))));
    }

    @Test
    void bodyCarriesTheCorrelationId() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "cid-abc-123");
        mvc.perform(get("/boom/unexpected"))
                .andExpect(jsonPath("$.correlationId").value("cid-abc-123"));
    }

    @Test
    void filterDenialsUseTheSameShape() throws Exception {
        // ApiError.write is what the auth filters now use — same shape, Jackson-escaped.
        var res = new org.springframework.mock.web.MockHttpServletResponse();
        ApiError.write(res, 403, "Forbidden", "Not a member of tenant '\"evil\"'");
        String body = res.getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"error\":\"Forbidden\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"status\":403"));
        // The embedded quote is escaped — no JSON injection from a spoofed header value.
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\\\"evil\\\""), body);
        org.junit.jupiter.api.Assertions.assertEquals(403, res.getStatus());
    }
}
