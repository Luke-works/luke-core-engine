package com.luke.engine.capability.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Opt-in IP-risk blocking: with {@code LUKE_SIGN_BLOCK_IP_RISK=TOR} and a reputation provider that
 * classifies every IP as TOR, the public sign POST is rejected 403 BEFORE signing and a BLOCKED
 * audit row (not a phantom SIGNED) is recorded — the request stays unsigned.
 */
@SpringBootTest(properties = "DB_URL=jdbc:h2:mem:sigblock;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(SignatureBlockTest.TorReputation.class)
class SignatureBlockTest extends SignatureCapabilityTestBase {

    private static final String KEYSTORE_B64;
    private static final String DOCSTORE_DIR;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        try {
            KEYSTORE_B64 = keystoreBase64();
            DOCSTORE_DIR = Files.createTempDirectory("sig-block-docstore").toString();
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
        registry.add("luke.sign.trust.tsa-enabled", () -> "false");
        registry.add("luke.sign.block-ip-risk", () -> "TOR"); // opt-in block
    }

    /** Overrides the default FreeIpReputationProvider so every IP classifies as TOR. */
    @TestConfiguration
    static class TorReputation {
        @Bean
        @Primary
        IpReputationProvider torReputation() {
            return ip -> IpRisk.TOR;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void grantAccess() {
        grantSignatures("t", "u");
    }

    @Test
    void blockedSignReturns403AndRecordsBlockedAudit() throws Exception {
        // create + send (authed paths are NOT IP-blocked)
        String meta = """
                {"name":"Blocked doc","signerEmail":"signer@example.com","signerName":"S",
                 "field":{"page":0,"x":10,"y":10,"w":100,"h":40}}""";
        MvcResult created = mvc.perform(multipart("/api/signatures")
                        .file(new MockMultipartFile("file", "b.pdf", "application/pdf", samplePdf()))
                        .param("json", meta).header("X-Tenant-Id", "t").header("X-User-Id", "u"))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        MvcResult sent = mvc.perform(post("/api/signatures/{id}/send", id)
                        .header("X-Tenant-Id", "t").header("X-User-Id", "u"))
                .andExpect(status().isOk()).andReturn();
        String signUrl = json.readTree(sent.getResponse().getContentAsString()).get("signUrl").asText();
        String token = signUrl.substring(signUrl.lastIndexOf("/sign/") + "/sign/".length());

        // public sign is blocked (403) — TOR is in the block set
        String body = json.writeValueAsString(java.util.Map.of(
                "signaturePngBase64", Base64.getEncoder().encodeToString(samplePng()), "consent", true));
        mvc.perform(post("/api/public/sign/{token}", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // the request is NOT signed, and the trail has a BLOCKED row (never a phantom SIGNED)
        MvcResult detail = mvc.perform(get("/api/signatures/{id}", id)
                        .header("X-Tenant-Id", "t").header("X-User-Id", "u"))
                .andExpect(status().isOk()).andReturn();
        JsonNode d = json.readTree(detail.getResponse().getContentAsString());
        assertEquals("SENT", d.get("request").get("status").asText(), "must not be COMPLETED");
        boolean hasBlocked = false, hasSigned = false;
        for (JsonNode e : d.get("audit")) {
            if ("BLOCKED".equals(e.get("action").asText())) hasBlocked = true;
            if ("SIGNED".equals(e.get("action").asText())) hasSigned = true;
        }
        assertTrue(hasBlocked, "expected a BLOCKED audit row");
        assertTrue(!hasSigned, "must NOT record a SIGNED row for a blocked attempt");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] samplePng() throws Exception {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String keystoreBase64() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        long now = System.currentTimeMillis();
        long day = 86_400_000L;
        X500Name dn = new X500Name("CN=Luke Block Test, O=Luke, C=US");
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(now), new Date(now - day), new Date(now + 3650L * day), dn, kp.getPublic());
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(cs));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("luke-sign", kp.getPrivate(), "changeit".toCharArray(), new Certificate[]{cert});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, "changeit".toCharArray());
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
