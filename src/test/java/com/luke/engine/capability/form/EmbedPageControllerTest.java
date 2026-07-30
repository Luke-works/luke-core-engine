package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(header().string("Content-Security-Policy",
                        "frame-ancestors https://acme.com https://*.acme.com; " + EmbedPageController.SCRIPT_AND_FRAME))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(Matchers.containsString("/embed-assets/embed.js")));
    }

    @Test
    void cspActuallyPermitsTheTurnstileScriptAndItsChallengeFrame() throws Exception {
        // Asserted against LITERAL directives, not against EmbedPageController.SCRIPT_AND_FRAME — a
        // test that compares the header to the constant it came from passes even when the constant is
        // wrong, which is exactly the failure mode that ships a policy blocking the widget.
        String token = tokens.sign("t1", "FM-3", 0);
        when(forms.findByTenantIdAndCode("t1", "FM-3")).thenReturn(Optional.of(form("t1", "FM-3", null)));

        String csp = mvc.perform(get("/embed/" + token).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(csp).isNotNull();
        // The widget loads a script from, and renders an iframe served by, challenges.cloudflare.com.
        assertThat(csp).contains("script-src 'self' https://challenges.cloudflare.com");
        assertThat(csp).contains("frame-src https://challenges.cloudflare.com");
        // The clickjacking control this page exists for must survive alongside them.
        assertThat(csp).contains("frame-ancestors");
        // The bundle is same-origin; granting inline script would hand any injected markup execution.
        assertThat(csp).doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
    }

    @Test
    void theShellCarriesNoInlineScriptSoScriptSrcSelfIsEnough() throws Exception {
        // `script-src 'self' …` grants no 'unsafe-inline', so an inline <script> in this page would be
        // blocked and the form would never boot. This is what lets the policy stay strict — and it is
        // the assertion the e2e CSP probe defers to, since Vite's dev server (which DOES inject inline
        // scripts) cannot stand in for the production shell.
        String token = tokens.sign("t1", "FM-4", 0);
        when(forms.findByTenantIdAndCode("t1", "FM-4")).thenReturn(Optional.of(form("t1", "FM-4", null)));

        String html = mvc.perform(get("/embed/" + token).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Every <script> in the shell must be a src= tag; none may carry a body.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<script([^>]*)>(.*?)</script>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
        int scripts = 0;
        while (m.find()) {
            scripts++;
            assertThat(m.group(1)).as("script tag must load from a src").contains("src=");
            assertThat(m.group(2).trim()).as("script tag must have no inline body").isEmpty();
        }
        assertThat(scripts).as("the shell boots the bundle with exactly one script tag").isEqualTo(1);
    }

    @Test
    void unrestrictedFormIsPublic() throws Exception {
        String token = tokens.sign("t1", "FM-2", 0);
        when(forms.findByTenantIdAndCode("t1", "FM-2")).thenReturn(Optional.of(form("t1", "FM-2", null)));
        mvc.perform(get("/embed/" + token).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        "frame-ancestors *; " + EmbedPageController.SCRIPT_AND_FRAME));
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
