package com.luke.engine.capability.signature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SIG-2 crux verification: the visual stamp + Certificate of Completion + SelfSeal PAdES seal +
 * RFC-3161 timestamp, end to end, with NO network and NO configured keystore — an ephemeral
 * signing cert is generated and the TSA runs in-process ({@link LocalTsaClient}), satisfying
 * the backlog's "configurable/mocked TSA in the test" requirement.
 */
class SigningEngineTest {

    private static final String DAY_MS = "86400000";

    @BeforeAll
    static void registerBc() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void signsSamplePdf_producesSignatureWithTimestampAndAuditCertificate() throws Exception {
        // ── ephemeral signer key + cert (stands in for the org PKCS#12) ──────────────
        KeyPair signerKp = rsa();
        X509Certificate signerCert = selfSigned(signerKp, "CN=Luke Signature TEST, O=Luke, C=US", false);
        SigningKeyProvider keyProvider = new SigningKeyProvider() {
            public PrivateKey privateKey() { return signerKp.getPrivate(); }
            public X509Certificate[] certificateChain() { return new X509Certificate[]{signerCert}; }
        };

        // ── in-process TSA (EKU=timeStamping), no HTTP ───────────────────────────────
        KeyPair tsaKp = rsa();
        X509Certificate tsaCert = selfSigned(tsaKp, "CN=Luke TSA TEST, O=Luke, C=US", true);
        TsaClient tsa = new LocalTsaClient(tsaKp.getPrivate(), tsaCert);

        SelfSealTrustProvider trust = new SelfSealTrustProvider(keyProvider, tsa, true);
        NativeSignatureProvider signer =
                new NativeSignatureProvider(trust, "Signed via Luke e-signature", "");

        // ── inputs ───────────────────────────────────────────────────────────────────
        byte[] source = samplePdf();
        String sourceSha256 = hex(MessageDigest.getInstance("SHA-256").digest(source));
        byte[] signaturePng = tinyPng();
        Field field = new Field(0, 72, 72, 160, 50);

        SignatureAuditEvent created = new SignatureAuditEvent("req-1", "tenant-1", "CREATED", "user:alice");
        created.setIpAddress("203.0.113.10");
        created.setIpRisk(IpRisk.CLEAN);
        created.setUserAgent("Mozilla/5.0 (Macintosh) TestAgent");
        SignatureAuditEvent signed = new SignatureAuditEvent("req-1", "tenant-1", "SIGNED", "signer@example.com");
        signed.setIpAddress("198.51.100.7");
        signed.setIpRisk(IpRisk.CLEAN);
        signed.setUserAgent("Chrome TestAgent");

        AuditMeta meta = new AuditMeta("Jane Signer", "signer@example.com", sourceSha256,
                List.of(created, signed));

        // ── act ──────────────────────────────────────────────────────────────────────
        byte[] sealed = signer.stampAndSign(new StampRequest(source, signaturePng, field, meta));
        assertNotNull(sealed);
        assertTrue(sealed.length > source.length, "sealed PDF should be larger than the source");

        // ── assert: valid PDF + signature dict + embedded RFC-3161 timestamp ─────────
        String text;
        try (PDDocument doc = Loader.loadPDF(sealed)) {
            assertTrue(doc.getNumberOfPages() >= 2, "expected the source page + a certificate page");

            List<PDSignature> sigs = doc.getSignatureDictionaries();
            assertFalse(sigs.isEmpty(), "expected a signature dictionary");
            PDSignature sig = sigs.get(0);
            assertEquals("ETSI.CAdES.detached", sig.getSubFilter(), "PAdES subfilter");

            byte[] cmsBytes = ((COSString) sig.getCOSObject().getDictionaryObject(COSName.CONTENTS)).getBytes();
            CMSSignedData cms = new CMSSignedData(new ByteArrayInputStream(cmsBytes));
            SignerInformation si = cms.getSignerInfos().getSigners().iterator().next();

            AttributeTable unsigned = si.getUnsignedAttributes();
            assertNotNull(unsigned, "expected unsigned attributes (timestamp lives here)");
            Attribute tsAttr = unsigned.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
            assertNotNull(tsAttr, "expected an RFC-3161 signature-time-stamp attribute");

            TimeStampToken token =
                    new TimeStampToken(ContentInfo.getInstance(tsAttr.getAttrValues().getObjectAt(0)));
            assertNotNull(token.getTimeStampInfo(), "timestamp token should parse");
            byte[] expectedImprint = MessageDigest.getInstance("SHA-256").digest(si.getSignature());
            assertArrayEquals(expectedImprint, token.getTimeStampInfo().getMessageImprintDigest(),
                    "timestamp must be over the signature value");

            text = new PDFTextStripper().getText(doc);
        }

        // ── assert: Certificate of Completion content (source hash + signer + trail) ──
        assertTrue(text.contains("Certificate of Completion"), "certificate title present");
        assertTrue(text.contains(sourceSha256), "source SHA-256 recorded on the certificate");
        assertTrue(text.contains("signer@example.com"), "signer recorded on the certificate");
    }

    @Test
    void signsWithMinimalCanvasPng_noPngConverterCrash() throws Exception {
        // This exact 1x1 PNG makes PDFBox 3.0.x PDImageXObject.createFromByteArray throw AIOOBE
        // via its PNGConverter fast-path — caught live, never by ImageIO-generated test PNGs.
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQAY3Z2pAAAAAElFTkSuQmCC");

        KeyPair kp = rsa();
        X509Certificate cert = selfSigned(kp, "CN=Luke Min PNG TEST, O=Luke, C=US", false);
        SigningKeyProvider keyProvider = new SigningKeyProvider() {
            public PrivateKey privateKey() { return kp.getPrivate(); }
            public X509Certificate[] certificateChain() { return new X509Certificate[]{cert}; }
        };
        TsaClient noTsa = bytes -> { throw new IllegalStateException("TSA must not be called"); };
        SelfSealTrustProvider trust = new SelfSealTrustProvider(keyProvider, noTsa, false);
        NativeSignatureProvider signer = new NativeSignatureProvider(trust, "Signed", "");

        AuditMeta meta = new AuditMeta("S", "s@example.com", "deadbeef", List.of());
        byte[] sealed = signer.stampAndSign(
                new StampRequest(samplePdf(), png, new Field(0, 72, 72, 160, 50), meta));

        try (PDDocument doc = Loader.loadPDF(sealed)) {
            assertFalse(doc.getSignatureDictionaries().isEmpty(), "minimal PNG must still produce a signed PDF");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private static final class LocalTsaClient implements TsaClient {
        private final PrivateKey key;
        private final X509Certificate cert;

        LocalTsaClient(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }

        @Override
        public TimeStampToken timeStamp(byte[] signatureValue) throws Exception {
            byte[] imprint = MessageDigest.getInstance("SHA-256").digest(signatureValue);
            TimeStampRequestGenerator reqGen = new TimeStampRequestGenerator();
            reqGen.setCertReq(true);
            TimeStampRequest request = reqGen.generate(TSPAlgorithms.SHA256, imprint);

            DigestCalculator digest = new JcaDigestCalculatorProviderBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
                    .get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256));
            SignerInfoGenerator sig = new JcaSimpleSignerInfoGeneratorBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build("SHA256withRSA", key, cert);

            TimeStampTokenGenerator tokenGen =
                    new TimeStampTokenGenerator(sig, digest, new ASN1ObjectIdentifier("1.2.3.4.1"));
            tokenGen.addCertificates(new JcaCertStore(List.of(cert)));

            TimeStampResponseGenerator respGen =
                    new TimeStampResponseGenerator(tokenGen, TSPAlgorithms.ALLOWED);
            TimeStampResponse response = respGen.generate(request, BigInteger.ONE, new Date());
            return response.getTimeStampToken();
        }
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair kp, String dn, boolean timeStamping) throws Exception {
        long now = System.currentTimeMillis();
        long day = Long.parseLong(DAY_MS);
        X500Name name = new X500Name(dn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), new Date(now - day), new Date(now + 3650L * day),
                name, kp.getPublic());
        if (timeStamping) {
            builder.addExtension(Extension.extendedKeyUsage, true,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        }
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(cs));
    }

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Sample agreement for the Luke e-signature SIG-2 test.");
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
        g.drawString("Jane Signer", 8, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
