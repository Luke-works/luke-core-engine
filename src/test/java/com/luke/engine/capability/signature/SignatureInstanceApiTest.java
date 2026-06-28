package com.luke.engine.capability.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Base64;
import javax.imageio.ImageIO;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-2 E2E for the campaign → instance → closure flow: publish a 2-signer definition (with a
 * required {{fullName}} variable), start a campaign (recipients + values), and verify the instance,
 * recipients, sequential routing, the Camunda outbox row, the validation gates, and the public
 * per-recipient signing that drives the contract to COMPLETED.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:siginst;DB_CLOSE_DELAY=-1",
        // This test drives signing through the service layer and asserts the outbox row stays
        // QUEUED; the Camunda hand-off is covered separately by SignatureCeremonyOutboxFlowTest.
        "luke.signatures.outbox-enabled=false"
})
@AutoConfigureMockMvc
class SignatureInstanceApiTest extends SignatureCapabilityTestBase {

    private static final String TENANT = "tenant-inst";
    private static final String USER = "user:sender";
    private static final String DOCSTORE_DIR;

    static {
        try {
            DOCSTORE_DIR = Files.createTempDirectory("sig-inst-docstore").toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final String KEYSTORE_B64 = TestKeystore.base64();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("luke.docstore.local-dir", () -> DOCSTORE_DIR);
        registry.add("luke.sign.trust.keystore-base64", () -> KEYSTORE_B64);
        registry.add("luke.sign.trust.keystore-password", () -> TestKeystore.PASSWORD);
        registry.add("luke.sign.trust.key-alias", () -> TestKeystore.ALIAS);
        registry.add("luke.sign.trust.tsa-enabled", () -> "false"); // offline
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    SignatureProcessOutboxRepository outboxRepo;

    @BeforeEach
    void grantAccess() {
        grantSignatures(TENANT, USER);
    }

    @Test
    void campaignToClosure() throws Exception {
        String code = publishTwoSignerDefinition();

        // START CAMPAIGN → instance SENT, 2 recipients, sequential routing
        String campaign = "{\"definitionCode\":\"" + code + "\","
                + "\"recipients\":[{\"signerId\":\"signer-1\",\"name\":\"Jane Client\",\"email\":\"jane@x.com\"},"
                + "{\"signerId\":\"signer-2\",\"name\":\"Carl Counter\",\"email\":\"carl@y.com\"}],"
                + "\"values\":{\"fullName\":\"Jane Client\"}}";
        JsonNode detail = json.readTree(mvc.perform(post("/api/signature-instances")
                        .contentType("application/json").content(campaign)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        JsonNode inst = detail.get("instance");
        String instId = inst.get("id").asText();
        assertEquals("SENT", inst.get("state").asText());
        assertEquals(code, inst.get("definitionCode").asText());
        assertEquals("Jane Client", inst.get("values").get("fullName").asText());
        String businessKey = inst.get("businessKey").asText();
        assertNotNull(businessKey);

        JsonNode rcpts = detail.get("recipients");
        assertEquals(2, rcpts.size());
        String token1 = tokenFor(rcpts, "signer-1");
        String token2 = tokenFor(rcpts, "signer-2");
        assertEquals("SENT", stateFor(rcpts, "signer-1"));
        assertEquals("PENDING", stateFor(rcpts, "signer-2"), "sequential: 2nd signer waits");

        // OUTBOX: a QUEUED Camunda-start row was written in the same tx
        assertTrue(outboxRepo.findByStateOrderByCreatedAtAsc("QUEUED").stream()
                .anyMatch(o -> businessKey.equals(o.getBusinessKey())), "outbox QUEUED row for the instance");

        // PUBLIC: signer-2 can't sign before signer-1 (sequential) → 409
        mvc.perform(get("/api/public/sign-instance/{t}", token2)).andExpect(status().isConflict());

        // PUBLIC: signer-1 opens → session has their field + the merged value
        JsonNode session = json.readTree(mvc.perform(get("/api/public/sign-instance/{t}", token1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("Jane Client", session.get("signerName").asText());
        assertTrue(session.get("pdfBase64").asText().length() > 100, "document returned");
        assertEquals(1, session.get("fields").size(), "only signer-1's field");
        assertEquals("Jane Client", session.get("values").get("fullName").asText());

        // signer-1 signs → instance IN_PROGRESS, signer-2 armed
        mvc.perform(post("/api/public/sign-instance/{t}", token1)
                        .contentType("application/json")
                        .content(json.writeValueAsString(new SignWrap(pngBase64(), true))))
                .andExpect(status().isOk());
        assertEquals("IN_PROGRESS", instanceState(instId));

        // signer-2 now allowed; signs → instance COMPLETED (closure)
        mvc.perform(get("/api/public/sign-instance/{t}", token2)).andExpect(status().isOk());
        mvc.perform(post("/api/public/sign-instance/{t}", token2)
                        .contentType("application/json")
                        .content(json.writeValueAsString(new SignWrap(pngBase64(), true))))
                .andExpect(status().isOk());
        assertEquals("COMPLETED", instanceState(instId));

        // SEAL ON CLOSURE: instance sealed, signed.pdf downloadable as a real PDF
        JsonNode completed = json.readTree(mvc.perform(get("/api/signature-instances/{id}", instId)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("SEALED", completed.get("instance").get("sealStatus").asText());

        byte[] sealed = mvc.perform(get("/api/signature-instances/{id}/signed.pdf", instId)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(sealed.length > 1000, "sealed PDF returned");
        assertEquals("%PDF-", new String(sealed, 0, 5), "starts with the PDF header");
        // a PAdES signature dict is present in the sealed bytes
        assertTrue(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1).contains("/Type /Sig")
                || new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1).contains("/Type/Sig"), "PAdES signature present");

        // already-signed link → 410
        mvc.perform(get("/api/public/sign-instance/{t}", token1)).andExpect(status().isGone());
    }

    @Test
    void campaignValidationGates() throws Exception {
        String code = publishTwoSignerDefinition();

        // missing recipient for signer-2 → 400
        mvc.perform(post("/api/signature-instances").contentType("application/json")
                        .content("{\"definitionCode\":\"" + code + "\",\"recipients\":[{\"signerId\":\"signer-1\",\"name\":\"A\",\"email\":\"a@x.com\"}],\"values\":{\"fullName\":\"A\"}}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isBadRequest());

        // missing required value {{fullName}} → 400
        mvc.perform(post("/api/signature-instances").contentType("application/json")
                        .content("{\"definitionCode\":\"" + code + "\",\"recipients\":[{\"signerId\":\"signer-1\",\"name\":\"A\",\"email\":\"a@x.com\"},{\"signerId\":\"signer-2\",\"name\":\"B\",\"email\":\"b@x.com\"}]}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void campaignOnUnpublishedDefinitionIsConflict() throws Exception {
        // a fresh draft-only definition
        String code = json.readTree(mvc.perform(post("/api/signature-definitions").contentType("application/json")
                        .content("{\"name\":\"Draft only\"}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("code").asText();

        mvc.perform(post("/api/signature-instances").contentType("application/json")
                        .content("{\"definitionCode\":\"" + code + "\",\"recipients\":[{\"signerId\":\"signer-1\",\"name\":\"A\",\"email\":\"a@x.com\"}]}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isConflict());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────
    /** create → checkout → upload doc → saveDraft(2 signers + required var + fields) → checkIn → sign-off → publish. */
    private String publishTwoSignerDefinition() throws Exception {
        JsonNode def = json.readTree(mvc.perform(post("/api/signature-definitions").contentType("application/json")
                        .content("{\"name\":\"Mutual NDA\"}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = def.get("id").asText();
        String code = def.get("code").asText();

        mvc.perform(post("/api/signature-definitions/{id}/checkout", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER)).andExpect(status().isOk());

        String key = json.readTree(mvc.perform(multipart("/api/signature-definitions/{id}/document", id)
                        .file(new MockMultipartFile("file", "nda.pdf", "application/pdf", samplePdf()))
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("key").asText();

        String schema = "{\"document\":{\"key\":\"" + key + "\",\"name\":\"nda.pdf\",\"pageCount\":1},"
                + "\"variables\":[{\"key\":\"fullName\",\"label\":\"Full name\",\"required\":true}],"
                + "\"signers\":[{\"id\":\"signer-1\",\"label\":\"Client\",\"order\":1,\"verify\":\"NONE\"},"
                + "{\"id\":\"signer-2\",\"label\":\"Counterparty\",\"order\":2,\"verify\":\"NONE\"}],"
                + "\"fields\":[{\"id\":\"f1\",\"signerId\":\"signer-1\",\"type\":\"SIGNATURE\",\"page\":0,\"x\":72,\"y\":120,\"w\":160,\"h\":50},"
                + "{\"id\":\"f2\",\"signerId\":\"signer-2\",\"type\":\"SIGNATURE\",\"page\":0,\"x\":72,\"y\":300,\"w\":160,\"h\":50}],"
                + "\"routing\":\"sequential\"}";
        mvc.perform(put("/api/signature-definitions/{id}/draft", id).contentType("application/json")
                        .content(json.writeValueAsString(new DraftWrap(schema)))
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER)).andExpect(status().isOk());
        mvc.perform(post("/api/signature-definitions/{id}/versions", id).contentType("application/json")
                        .content(json.writeValueAsString(new DraftWrap(schema)))
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER)).andExpect(status().isOk());
        mvc.perform(post("/api/signature-definitions/{id}/sign-off", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER)).andExpect(status().isOk());
        mvc.perform(post("/api/signature-definitions/{id}/versions/{v}/publish", id, 1)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER)).andExpect(status().isOk());
        return code;
    }

    private String instanceState(String instId) throws Exception {
        return json.readTree(mvc.perform(get("/api/signature-instances/{id}", instId)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("instance").get("state").asText();
    }

    private static String tokenFor(JsonNode recipients, String signerId) {
        for (JsonNode r : recipients) if (signerId.equals(r.get("signerId").asText())) return r.get("signToken").asText();
        throw new AssertionError("no recipient " + signerId);
    }

    private static String stateFor(JsonNode recipients, String signerId) {
        for (JsonNode r : recipients) if (signerId.equals(r.get("signerId").asText())) return r.get("state").asText();
        throw new AssertionError("no recipient " + signerId);
    }

    private record DraftWrap(String schema) {}
    private record SignWrap(String signaturePngBase64, boolean consent) {}

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Contract.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String pngBase64() throws Exception {
        BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.drawLine(0, 0, 119, 39);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
