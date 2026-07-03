package com.luke.engine.capability.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SIG-3 full-stack E2E: create → send → public view → public sign → download, through the real
 * REST controllers, SignatureService, LocalFsDocumentStore (temp dir), and the real signing engine
 * (a generated test PKCS#12 is injected; the TSA is disabled to stay offline — the timestamp path
 * is proven separately by SigningEngineTest). Asserts status transitions, retainUntil, the audit
 * trail, and that the downloaded PDF is real and signed.
 */
@SpringBootTest(properties = "DB_URL=jdbc:h2:mem:sige2e;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SignatureApiE2ETest extends SignatureCapabilityTestBase {

    private static final String TENANT = "tenant-e2e";
    private static final String USER = "user:requester";

    private static final String KEYSTORE_B64;
    private static final String DOCSTORE_DIR;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        try {
            KEYSTORE_B64 = generateKeystoreBase64();
            DOCSTORE_DIR = Files.createTempDirectory("sig-e2e-docstore").toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("luke.docstore.local-dir", () -> DOCSTORE_DIR);
        registry.add("luke.sign.trust.keystore-base64", () -> KEYSTORE_B64);
        registry.add("luke.sign.trust.keystore-password", () -> "changeit");
        registry.add("luke.sign.trust.key-alias", () -> "luke-sign");
        registry.add("luke.sign.trust.tsa-enabled", () -> "false"); // offline: no RFC-3161 call
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void grantAccess() {
        grantSignatures(TENANT, USER);
        grantSignatures("tenant-A", "a");
        grantSignatures("tenant-B", "b");
    }

    @Test
    void fullSigningFlow() throws Exception {
        byte[] sourcePdf = samplePdf();

        // 1) CREATE (multipart) → DRAFT, retainUntil set
        String meta = """
                {"name":"Mutual NDA","signerEmail":"signer@example.com","signerName":"Jane Signer",
                 "field":{"page":0,"x":72,"y":72,"w":160,"h":50}}""";
        MvcResult created = mvc.perform(multipart("/api/signatures")
                        .file(new MockMultipartFile("file", "nda.pdf", "application/pdf", sourcePdf))
                        .param("json", meta)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", USER))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdBody = json.readTree(created.getResponse().getContentAsString());
        String id = createdBody.get("id").asText();
        assertEquals("DRAFT", createdBody.get("status").asText());
        assertTrue(createdBody.hasNonNull("retainUntil"), "retainUntil must be set at create");
        assertTrue(createdBody.get("retainUntil").asLong() > createdBody.get("createdAt").asLong());
        assertTrue(createdBody.get("code").asText().startsWith("SR-"));
        // contract: never leak storage keys / token / tenantId
        assertFalse(createdBody.has("sourceObjectKey"), "must not leak storage keys");
        assertFalse(createdBody.has("signToken"), "must not leak the sign token");
        assertFalse(createdBody.has("tenantId"), "must not leak tenantId");

        // 2) SEND → signUrl with token
        MvcResult sent = mvc.perform(post("/api/signatures/{id}/send", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andReturn();
        String signUrl = json.readTree(sent.getResponse().getContentAsString()).get("signUrl").asText();
        String token = signUrl.substring(signUrl.lastIndexOf("/sign/") + "/sign/".length());
        assertEquals(32, token.length(), "sign token should be 32 chars");

        // 3) PUBLIC VIEW (no tenant header) → marks VIEWED, returns source pdf + field
        MvcResult viewed = mvc.perform(get("/api/public/sign/{token}", token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode session = json.readTree(viewed.getResponse().getContentAsString());
        assertEquals("Jane Signer", session.get("signerName").asText());
        assertFalse(session.get("pdfBase64").asText().isBlank(), "source pdf must be returned");
        assertEquals(0, session.get("field").get("page").asInt());
        assertFalse(session.get("verification").get("required").asBoolean(), "V1 = no verification");

        // 4) PUBLIC SIGN → ok
        String body = json.writeValueAsString(java.util.Map.of(
                "signaturePngBase64", Base64.getEncoder().encodeToString(tinyPng()),
                "consent", true,
                "signerNameTyped", "Jane Signer"));
        mvc.perform(post("/api/public/sign/{token}", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":true")));

        // 5) GET detail → COMPLETED + audit trail with the full lifecycle
        MvcResult detail = mvc.perform(get("/api/signatures/{id}", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detailBody = json.readTree(detail.getResponse().getContentAsString());
        assertEquals("COMPLETED", detailBody.get("request").get("status").asText());
        assertTrue(detailBody.get("request").hasNonNull("signedAt"));
        JsonNode audit = detailBody.get("audit");
        assertActionsPresent(audit, "CREATED", "SENT", "VIEWED", "SIGNED");
        // audit rows carry the IP-stamped fields
        assertTrue(audit.get(0).has("ipAddress"));
        assertTrue(audit.get(0).has("at"));

        // 6) DOWNLOAD signed.pdf → application/pdf, real signed PDF
        MvcResult dl = mvc.perform(get("/api/signatures/{id}/signed.pdf", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn();
        byte[] signedPdf = dl.getResponse().getContentAsByteArray();
        assertTrue(signedPdf.length > sourcePdf.length, "signed PDF should be non-empty and larger");
        try (PDDocument doc = Loader.loadPDF(signedPdf)) {
            assertFalse(doc.getSignatureDictionaries().isEmpty(), "downloaded PDF must carry a signature");
            assertTrue(doc.getNumberOfPages() >= 2, "source page + certificate of completion");
        }

        // DOWNLOAD before completion is 409 — re-sign attempt on a completed token is 410
        mvc.perform(post("/api/public/sign/{token}", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isGone());
    }

    @Test
    void tenantIsolation_otherTenantCannotSee() throws Exception {
        byte[] sourcePdf = samplePdf();
        String meta = """
                {"name":"Tenant A doc","signerEmail":"a@example.com","signerName":"A",
                 "field":{"page":0,"x":10,"y":10,"w":100,"h":40}}""";
        MvcResult created = mvc.perform(multipart("/api/signatures")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", sourcePdf))
                        .param("json", meta)
                        .header("X-Tenant-Id", "tenant-A").header("X-User-Id", "a"))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // tenant-B must get 404, not the row
        mvc.perform(get("/api/signatures/{id}", id)
                        .header("X-Tenant-Id", "tenant-B").header("X-User-Id", "b"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendIsIdempotent_copyLinkDoesNotReAuditOrRebump() throws Exception {
        String meta = """
                {"name":"Idempotent","signerEmail":"s@example.com","signerName":"S",
                 "field":{"page":0,"x":10,"y":10,"w":100,"h":40}}""";
        MvcResult created = mvc.perform(multipart("/api/signatures")
                        .file(new MockMultipartFile("file", "i.pdf", "application/pdf", samplePdf()))
                        .param("json", meta).header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        String url1 = signUrlFromSend(id);
        String url2 = signUrlFromSend(id); // "copy link" — must be a no-op returning the same link
        assertEquals(url1, url2, "re-send must return the same signing link");

        MvcResult detail = mvc.perform(get("/api/signatures/{id}", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn();
        long sentCount = 0;
        for (JsonNode e : json.readTree(detail.getResponse().getContentAsString()).get("audit")) {
            if ("SENT".equals(e.get("action").asText())) sentCount++;
        }
        assertEquals(1, sentCount, "re-send must NOT record a second SENT audit event");
    }

    private String signUrlFromSend(String id) throws Exception {
        MvcResult r = mvc.perform(post("/api/signatures/{id}/send", id)
                        .header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("signUrl").asText();
    }

    @Test
    void rejectsNonPdfUpload() throws Exception {
        String meta = """
                {"name":"x","signerEmail":"a@b.com","signerName":"A","field":{"page":0,"x":1,"y":1,"w":10,"h":10}}""";
        mvc.perform(multipart("/api/signatures")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", "NOT A PDF".getBytes()))
                        .param("json", meta).header("X-Tenant-Id", TENANT).header("X-User-Id", USER))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectsOversizedSignaturePng() throws Exception {
        // > 512KB base64 → 413 in decodePng, BEFORE token resolution (DoS guard on the public route)
        String huge = "A".repeat(800 * 1024);
        String body = json.writeValueAsString(java.util.Map.of("signaturePngBase64", huge, "consent", true));
        mvc.perform(post("/api/public/sign/{token}", "anytoken")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rejectsNonPngSignature() throws Exception {
        String notPng = Base64.getEncoder().encodeToString("hello".getBytes());
        String body = json.writeValueAsString(java.util.Map.of("signaturePngBase64", notPng, "consent", true));
        mvc.perform(post("/api/public/sign/{token}", "anytoken")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private void assertActionsPresent(JsonNode auditArray, String... actions) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        auditArray.forEach(n -> seen.add(n.get("action").asText()));
        for (String a : actions) {
            assertTrue(seen.contains(a), "audit trail missing action " + a + " (saw " + seen + ")");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("SIG-3 E2E sample document.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] tinyPng() throws Exception {
        BufferedImage img = new BufferedImage(160, 50, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.drawString("Jane", 10, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String generateKeystoreBase64() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        long day = 86_400_000L;
        X500Name dn = new X500Name("CN=Luke Signature E2E, O=Luke, C=US");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(now), new Date(now - day), new Date(now + 3650L * day), dn, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("luke-sign", kp.getPrivate(), "changeit".toCharArray(), new Certificate[]{cert});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, "changeit".toCharArray());
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
