package com.luke.engine.recipient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The recipient portal page is a top-level shell that boots the standalone bundle and blocks framing. */
class PortalPageControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PortalPageController()).build();

    @Test
    void servesTheShellForAnyHandleAndBlocksFraming() throws Exception {
        mvc.perform(get("/portal/ptl_abc123"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(Matchers.containsString("/portal-assets/portal.js")))
                .andExpect(content().string(Matchers.containsString("/portal-assets/portal.css")));
    }
}
