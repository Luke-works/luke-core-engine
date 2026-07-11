package com.luke.engine.capability.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The SIG-M payoff: the signature ceremony IS a real Camunda process. Proves the durable
 * campaign→process→closure chain end-to-end:
 *   1. starting a campaign writes a QUEUED outbox row (same tx as the instance),
 *   2. the consumer starts the ceremony process IN-PROCESS (parked at "Await Signatures") and
 *      flips the row to STARTED, stamping processInstanceId back on the instance,
 *   3. once every recipient signs (instance COMPLETED), the consumer correlates the
 *      SignatureClosure message → the process finishes and the row goes CLOSED.
 * The @Scheduled poller is disabled so the test drives the consumer deterministically.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:sigceremony;DB_CLOSE_DELAY=-1",
        "luke.signatures.outbox-enabled=false"
})
@AutoConfigureMockMvc
class SignatureCeremonyOutboxFlowTest extends SignatureCapabilityTestBase {

    private static final String TENANT = "t-sig-ceremony";
    private static final String USER = "user:sender";
    private static final String DOCSTORE_DIR;

    static {
        try {
            DOCSTORE_DIR = Files.createTempDirectory("sig-ceremony-docstore").toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("luke.docstore.local-dir", () -> DOCSTORE_DIR);
        registry.add("luke.sign.trust.keystore-base64", TestKeystore::base64);
        registry.add("luke.sign.trust.keystore-password", () -> TestKeystore.PASSWORD);
        registry.add("luke.sign.trust.key-alias", () -> TestKeystore.ALIAS);
        registry.add("luke.sign.trust.tsa-enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SignatureProcessOutboxRepository outbox;
    @Autowired SignatureInstanceRepository instances;
    @Autowired SignatureProcessOutboxConsumer consumer;
    @Autowired SignatureProcessDeployer deployer;
    @Autowired RuntimeService runtimeService;

    @BeforeEach
    void grantAccess() {
        grantSignatures(TENANT, USER);
    }

    @Test
    void campaignStartsCeremony_andClosureFinishesIt() throws Exception {
        deployer.deployFor(TENANT); // the boot backfill can't know a tenant created mid-test

        String code = publishOneSignerDefinition();

        // START CAMPAIGN → instance SENT + a QUEUED outbox row (poller disabled, so it stays)
        JsonNode detail = json.readTree(mvc.perform(post("/api/signature-instances")
                        .contentType("application/json")
                        .content("{\"definitionCode\":\"" + code + "\",\"recipients\":["
                                + "{\"signerId\":\"signer-1\",\"name\":\"Jane\",\"email\":\"jane@x.com\"}]}")
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String instId = detail.get("instance").get("id").asText();
        String businessKey = detail.get("instance").get("businessKey").asText();
        String token = detail.get("recipients").get(0).get("signToken").asText();

        SignatureProcessOutbox queued = outbox.findByStateOrderByCreatedAtAsc(SignatureProcessOutbox.QUEUED)
                .stream().filter(o -> businessKey.equals(o.getBusinessKey())).findFirst().orElseThrow();

        // PHASE 1: consumer starts the ceremony → STARTED + processInstanceId, process parked
        consumer.start(queued);
        SignatureProcessOutbox started = outbox.findById(queued.getId()).orElseThrow();
        assertEquals(SignatureProcessOutbox.STARTED, started.getState());
        assertNotNull(started.getProcessInstanceId());
        assertEquals(SignatureProcessOutbox.STARTED,
                instances.findById(instId).orElseThrow().getProcessStatus());
        assertEquals(1, runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count(), "ceremony parked at the receive task");

        // SIGN (public) → only recipient signs → instance COMPLETED (terminal)
        mvc.perform(post("/api/public/sign-instance/{t}", token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(new SignWrap(pngBase64(), true))))
                .andExpect(status().isOk());
        assertEquals(SignatureInstanceStates.COMPLETED,
                instances.findById(instId).orElseThrow().getState());

        // PHASE 2: consumer sees the terminal instance → correlates closure → CLOSED, process gone
        consumer.maybeClose(started);
        SignatureProcessOutbox closed = outbox.findById(queued.getId()).orElseThrow();
        assertEquals(SignatureProcessOutbox.CLOSED, closed.getState());
        assertEquals(SignatureProcessOutbox.CLOSED,
                instances.findById(instId).orElseThrow().getProcessStatus());
        assertEquals(0, runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count(), "ceremony finished on closure");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────
    private String publishOneSignerDefinition() throws Exception {
        JsonNode def = json.readTree(mvc.perform(post("/api/signature-definitions").contentType("application/json")
                        .content("{\"name\":\"NDA\"}")
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
                + "\"signers\":[{\"id\":\"signer-1\",\"label\":\"Client\",\"order\":1,\"verify\":\"NONE\"}],"
                + "\"fields\":[{\"id\":\"f1\",\"signerId\":\"signer-1\",\"type\":\"SIGNATURE\",\"page\":0,\"x\":72,\"y\":120,\"w\":160,\"h\":50}],"
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
                cs.showText("Ceremony contract.");
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
