package com.luke.engine.capability.form;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The embed page must carry the correct per-tenant frame-ancestors policy and never serve a forged
 *  link as framable. Standalone MockMvc — real EmbedTokens (test secret) + a mocked repository. */
class EmbedPageControllerTest {

    private final EmbedTokens tokens = new EmbedTokens("test-secret");
    private FormDefinitionRepository forms;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        forms = Mockito.mock(FormDefinitionRepository.class);
        // The embed-site recorder is pure bookkeeping for the author's "Embedded on" list; a mock keeps
        // these CSP tests about the header they assert.
        FormEmbedSiteRecorder embedSites = Mockito.mock(FormEmbedSiteRecorder.class);
        mvc = MockMvcBuilders.standaloneSetup(new EmbedPageController(tokens, forms, embedSites)).build();
    }

    private FormDefinition form(String tenant, String code, String allowedOrigins) {
        FormDefinition f = new FormDefinition();
        f.setTenantId(tenant);
        f.setCode(code);
        f.setAllowedEmbedOrigins(allowedOrigins);
        return f;
    }

    @Test
    void restrictedFormEmitsItsAllowlistAsFrameAncestorsAndBootsTheBundle() throws Exception {
        String token = tokens.sign("t1", "FM-1", 0);
        when(forms.findByTenantIdAndCode("t1", "FM-1"))
                .thenReturn(Optional.of(form("t1", "FM-1", "https://acme.com,https://*.acme.com")));
        mvc.perform(get("/embed/" + token).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors https://acme.com https://*.acme.com"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(Matchers.containsString("/embed-assets/embed.js")));
    }

    @Test
    void unrestrictedFormIsPublic() throws Exception {
        String token = tokens.sign("t1", "FM-2", 0);
        when(forms.findByTenantIdAndCode("t1", "FM-2")).thenReturn(Optional.of(form("t1", "FM-2", null)));
        mvc.perform(get("/embed/" + token).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors *"));
    }

    @Test
    void forgedTokenIsNotFoundAndNotFramable() throws Exception {
        mvc.perform(get("/embed/not-a-real-token").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"));
    }

    @Test
    void unknownOrDeletedFormIsNotFound() throws Exception {
        String token = tokens.sign("t1", "FM-GONE", 0);
        when(forms.findByTenantIdAndCode("t1", "FM-GONE")).thenReturn(Optional.empty());
        mvc.perform(get("/embed/" + token).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
    }

    @Test
    void revokedTokenIsNotFound() throws Exception {
        String token = tokens.sign("t1", "FM-R", 0); // minted at key version 0
        FormDefinition f = form("t1", "FM-R", null);
        f.setEmbedKeyVersion(1); // form's embed key was rotated → the v0 token is dead
        when(forms.findByTenantIdAndCode("t1", "FM-R")).thenReturn(Optional.of(f));
        mvc.perform(get("/embed/" + token).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
    }
}
