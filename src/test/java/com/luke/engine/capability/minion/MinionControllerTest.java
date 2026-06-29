package com.luke.engine.capability.minion;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luke.engine.capability.form.EmbedFormResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/**
 * The minion proxy must dispatch by operation, require a tenant on the authed path, and — on the public
 * path — resolve the tenant from the embed token and expose ONLY public-allowed minions. Standalone
 * MockMvc with stub minions + a mocked token resolver.
 */
class MinionControllerTest {

    /** A public-allowed echo minion: returns the resolved tenant + the query, so the test can prove
     *  the controller passed the SERVER-resolved tenant (not anything from the browser). */
    private static final Minion PUBLIC_ECHO = new Minion() {
        @Override public String name() { return "echo"; }
        @Override public boolean publicAllowed() { return true; }
        @Override public Object handle(String tenantId, Map<String, Object> params) {
            return Map.of("tenant", tenantId, "q", String.valueOf(params.get("q")));
        }
    };
    /** An internal-only minion — reachable on the authed path, NOT via the public/embed path. */
    private static final Minion INTERNAL = new Minion() {
        @Override public String name() { return "secret"; }
        @Override public Object handle(String tenantId, Map<String, Object> params) { return Map.of("ok", true); }
    };

    private EmbedFormResolver resolver;
    private MockMvc authed;
    private MockMvc publicMvc;

    @BeforeEach
    void setup() {
        MinionRegistry registry = new MinionRegistry(List.of(PUBLIC_ECHO, INTERNAL));
        MinionRateLimiter limiter = new MinionRateLimiter();
        resolver = Mockito.mock(EmbedFormResolver.class);
        authed = MockMvcBuilders.standaloneSetup(new MinionController(registry, limiter)).build();
        publicMvc = MockMvcBuilders.standaloneSetup(new PublicMinionController(resolver, registry, limiter)).build();
    }

    // ── Authed path ───────────────────────────────────────────────────────────────────
    @Test
    void authedCallDispatchesWithTheHeaderTenant() throws Exception {
        authed.perform(post("/api/minions/echo").header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value("t1"))
                .andExpect(jsonPath("$.q").value("hello"));
    }

    @Test
    void authedCallWithoutTenantIs400() throws Exception {
        authed.perform(post("/api/minions/echo").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownMinionIs404() throws Exception {
        authed.perform(post("/api/minions/nope").header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    // ── Public path ───────────────────────────────────────────────────────────────────
    @Test
    void publicCallResolvesTenantFromTokenAndRunsAPublicMinion() throws Exception {
        when(resolver.resolveTenant("tok")).thenReturn("t1");
        publicMvc.perform(post("/api/public/minions/tok/echo")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"221b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value("t1")) // tenant came from the token, not the request
                .andExpect(jsonPath("$.q").value("221b"));
    }

    @Test
    void publicCallToAnInternalOnlyMinionIs404() throws Exception {
        when(resolver.resolveTenant("tok")).thenReturn("t1");
        publicMvc.perform(post("/api/public/minions/tok/secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicCallWithAForgedTokenIs404() throws Exception {
        when(resolver.resolveTenant("bad")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown link."));
        publicMvc.perform(post("/api/public/minions/bad/echo")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
