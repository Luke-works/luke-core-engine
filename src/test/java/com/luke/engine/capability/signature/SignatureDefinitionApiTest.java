package com.luke.engine.capability.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase-1 design-time E2E for the signature DEFINITION lifecycle through the real REST controller,
 * service, repositories, and LocalFsDocumentStore (temp dir): create → checkout → upload document →
 * save draft → check-in → (publish blocked) → sign-off → publish, plus the advisory-lock gate,
 * tenant isolation, and the audit trail. Mirrors the forms FormDefinition lifecycle.
 */
@SpringBootTest(properties = "DB_URL=jdbc:h2:mem:sigdef;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SignatureDefinitionApiTest extends SignatureCapabilityTestBase {

    private static final String TENANT = "tenant-defs";
    private static final String OTHER_TENANT = "tenant-other";
    private static final String USER = "user:designer";
    private static final String DOCSTORE_DIR;

    static {
        try {
            DOCSTORE_DIR = Files.createTempDirectory("sig-def-docstore").toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("luke.docstore.local-dir", () -> DOCSTORE_DIR);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void grantAccess() {
        grantSignatures(TENANT, USER);
        grantSignatures(TENANT, "user:alice");
        grantSignatures(TENANT, "user:bob");
        grantSignatures(OTHER_TENANT, USER);
        grantSignatures("tenant-del", USER);
    }

    @Test
    void designTimeLifecycle() throws Exception {
        // 1) CREATE → draft, SD- code, latestVersion 0, no tenant leak
        MvcResult created = mvc.perform(post("/api/signature-definitions")
                        .contentType("application/json")
                        .content("{\"name\":\"Mutual NDA\",\"description\":\"two-party\"}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode def = json.readTree(created.getResponse().getContentAsString());
        String id = def.get("id").asText();
        assertEquals("draft", def.get("status").asText());
        assertTrue(def.get("code").asText().startsWith("SD-"));
        assertEquals(0, def.get("latestVersion").asInt());
        assertFalse(def.get("latestVersionSignedOff").asBoolean());
        assertFalse(def.has("tenantId"), "must not leak tenantId");

        // 2) saveDraft WITHOUT checkout → 409 (view-only until checked out)
        mvc.perform(put("/api/signature-definitions/{id}/draft", id)
                        .contentType("application/json").content("{\"schema\":\"{}\"}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isConflict());

        // 3) CHECKOUT → lock acquired
        MvcResult checkedOut = mvc.perform(post("/api/signature-definitions/{id}/checkout", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(USER, json.readTree(checkedOut.getResponse().getContentAsString()).get("lockedBy").asText());

        // 4) UPLOAD DOCUMENT → key + pageCount, then FETCH it back as a PDF
        MvcResult up = mvc.perform(multipart("/api/signature-definitions/{id}/document", id)
                        .file(new MockMultipartFile("file", "nda.pdf", "application/pdf", samplePdf()))
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode uploaded = json.readTree(up.getResponse().getContentAsString());
        String key = uploaded.get("key").asText();
        assertEquals(1, uploaded.get("pageCount").asInt());
        mvc.perform(get("/api/signature-definitions/{id}/document/{key}", id, key)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(status().isOk());

        // 5) SAVE DRAFT (now allowed) — schema references the uploaded key
        String schema = "{\"document\":{\"key\":\"" + key + "\",\"name\":\"nda.pdf\",\"pageCount\":1},"
                + "\"signers\":[{\"id\":\"signer-1\",\"label\":\"Client\",\"order\":1}],"
                + "\"fields\":[{\"id\":\"f1\",\"signerId\":\"signer-1\",\"type\":\"SIGNATURE\",\"page\":0,\"x\":72,\"y\":100,\"w\":160,\"h\":50}],"
                + "\"routing\":\"sequential\"}";
        mvc.perform(put("/api/signature-definitions/{id}/draft", id)
                        .contentType("application/json").content(json.writeValueAsString(new DraftWrap(schema)))
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk());

        // 6) CHECK IN → version 1; lock released
        MvcResult ci = mvc.perform(post("/api/signature-definitions/{id}/versions", id)
                        .contentType("application/json").content(json.writeValueAsString(new DraftWrap(schema)))
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(1, json.readTree(ci.getResponse().getContentAsString()).get("version").asInt());
        JsonNode afterCi = getDef(id);
        assertEquals(1, afterCi.get("latestVersion").asInt());
        assertFalse(afterCi.get("latestVersionSignedOff").asBoolean());
        assertTrue(afterCi.get("lockedBy").isNull(), "check-in releases the lock");

        // 7) PUBLISH before sign-off → 409
        mvc.perform(post("/api/signature-definitions/{id}/versions/{v}/publish", id, 1)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isConflict());

        // 8) SIGN OFF (legal review) → latest signed off
        mvc.perform(post("/api/signature-definitions/{id}/sign-off", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk());
        assertTrue(getDef(id).get("latestVersionSignedOff").asBoolean());

        // 9) PUBLISH → published, publishedVersion 1
        mvc.perform(post("/api/signature-definitions/{id}/versions/{v}/publish", id, 1)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk());
        JsonNode published = getDef(id);
        assertEquals("published", published.get("status").asText());
        assertEquals(1, published.get("publishedVersion").asInt());

        // 10) AUDIT trail records the lifecycle
        MvcResult auditRes = mvc.perform(get("/api/signature-definitions/{id}/audit", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andReturn();
        String actions = auditRes.getResponse().getContentAsString();
        assertTrue(actions.contains("created"));
        assertTrue(actions.contains("checked_in"));
        assertTrue(actions.contains("signed_off"));
        assertTrue(actions.contains("published"));

        // 11) TENANT ISOLATION — another tenant can't see it
        mvc.perform(get("/api/signature-definitions/{id}", id)
                        .header("X-Tenant-Id", OTHER_TENANT).header("X-User-Id", USER))
                .andExpect(status().isNotFound());
    }

    @Test
    void lockHeldByAnotherUserBlocksCheckoutUnlessForced() throws Exception {
        String id = json.readTree(mvc.perform(post("/api/signature-definitions")
                        .contentType("application/json").content("{\"name\":\"Lease\"}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", "user:alice"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/signature-definitions/{id}/checkout", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", "user:alice"))
                .andExpect(status().isOk());

        // bob can't check out (held by alice) without force
        mvc.perform(post("/api/signature-definitions/{id}/checkout", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", "user:bob"))
                .andExpect(status().isConflict());

        // ...but force takes it over
        mvc.perform(post("/api/signature-definitions/{id}/checkout", id).param("force", "true")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", "user:bob"))
                .andExpect(status().isOk());
    }

    @Test
    void softDeleteHidesFromDefaultListButShowsWhenDeletedRequested() throws Exception {
        String id = json.readTree(mvc.perform(post("/api/signature-definitions")
                        .contentType("application/json").content("{\"name\":\"Temp\"}")
                        .header("X-Tenant-Id", "tenant-del").header("X-User-Id", USER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(delete("/api/signature-definitions/{id}", id)
                        .header("X-Tenant-Id", "tenant-del").header("X-User-Id", USER))
                .andExpect(status().isOk());

        String live = mvc.perform(get("/api/signature-definitions")
                        .header("X-Tenant-Id", "tenant-del").header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(live.contains(id), "soft-deleted definition hidden from default list");

        String withDeleted = mvc.perform(get("/api/signature-definitions").param("deleted", "true")
                        .header("X-Tenant-Id", "tenant-del").header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(withDeleted.contains(id), "deleted=true surfaces it");
    }

    // patch metadata smoke (also proves @RequestBody MetaPatch binding)
    @Test
    void patchUpdatesName() throws Exception {
        String id = json.readTree(mvc.perform(post("/api/signature-definitions")
                        .contentType("application/json").content("{\"name\":\"Old\"}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        MvcResult patched = mvc.perform(patch("/api/signature-definitions/{id}", id)
                        .contentType("application/json").content("{\"name\":\"New name\"}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn();
        assertEquals("New name", json.readTree(patched.getResponse().getContentAsString()).get("name").asText());
    }

    private JsonNode getDef(String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/signature-definitions/{id}", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private record DraftWrap(String schema) {}

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Definition source document.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
