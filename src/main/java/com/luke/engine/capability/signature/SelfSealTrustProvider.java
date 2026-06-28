package com.luke.engine.capability.signature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Hashtable;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V1 default {@link TrustProvider} (selected unless {@code LUKE_SIGN_TRUST_MODE=qtsp}). Applies
 * a PAdES detached signature to the prepared PDF — a CMS SignedData ({@code ETSI.CAdES.detached})
 * over the document byte range, signed with the org PKCS#12 ({@link SigningKeyProvider}), with an
 * RFC-3161 trusted timestamp ({@link TsaClient}) embedded as the {@code signature-time-stamp}
 * unsigned attribute. Includes the {@code signing-certificate-v2} signed attribute (CAdES-BES).
 *
 * <p>US ESIGN/UETA-binding at ≈ $0/signature. The Adobe "green check" additionally needs an
 * AATL-member CA cert (annual, not per-signature); EU eIDAS QES needs {@code TRUST_MODE=qtsp}.
 */
@Component
@ConditionalOnProperty(name = "luke.sign.trust.mode", havingValue = "self", matchIfMissing = true)
public class SelfSealTrustProvider implements TrustProvider {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final SigningKeyProvider keys;
    private final TsaClient tsaClient;
    private final boolean tsaEnabled;

    public SelfSealTrustProvider(SigningKeyProvider keys,
                                 TsaClient tsaClient,
                                 @Value("${luke.sign.trust.tsa-enabled:true}") boolean tsaEnabled) {
        this.keys = keys;
        this.tsaClient = tsaClient;
        this.tsaEnabled = tsaEnabled;
    }

    @Override
    public byte[] seal(byte[] preparedPdf, SealMeta meta) {
        try (PDDocument document = Loader.loadPDF(preparedPdf)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED); // PAdES
            if (meta.signerName() != null) signature.setName(meta.signerName());
            if (meta.reason() != null && !meta.reason().isBlank()) signature.setReason(meta.reason());
            if (meta.location() != null && !meta.location().isBlank()) signature.setLocation(meta.location());
            signature.setSignDate(Calendar.getInstance());

            // Reserve generous space — a CMS with the signer chain + an embedded RFC-3161 token
            // is well over PDFBox's default placeholder.
            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(0x10000); // 64 KB

            document.addSignature(signature, new CadesSigner(), options);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.saveIncremental(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PAdES seal failed: " + e.getMessage(), e);
        }
    }

    /** PDFBox calls {@link #sign} with the document byte range; we return the detached CMS. */
    private final class CadesSigner implements SignatureInterface {
        @Override
        public byte[] sign(InputStream content) throws IOException {
            try {
                byte[] data = content.readAllBytes();
                X509Certificate[] chain = keys.certificateChain();
                X509Certificate signerCert = chain[0];
                PrivateKey privateKey = keys.privateKey();

                ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(privateKey);
                DigestCalculatorProvider digestProvider = new JcaDigestCalculatorProviderBuilder()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build();

                // signing-certificate-v2 (CAdES-BES) signed attribute. The default generator
                // adds content-type + message-digest automatically.
                byte[] certHash = MessageDigest.getInstance("SHA-256").digest(signerCert.getEncoded());
                SigningCertificateV2 scv2 = new SigningCertificateV2(new ESSCertIDv2(certHash));
                Attribute signingCertAttr = new Attribute(
                        PKCSObjectIdentifiers.id_aa_signingCertificateV2, new DERSet(scv2));
                Hashtable<ASN1ObjectIdentifier, Attribute> signed = new Hashtable<>();
                signed.put(signingCertAttr.getAttrType(), signingCertAttr);

                SignerInfoGenerator signerInfoGen = new JcaSignerInfoGeneratorBuilder(digestProvider)
                        .setSignedAttributeGenerator(
                                new DefaultSignedAttributeTableGenerator(new AttributeTable(signed)))
                        .build(contentSigner, new X509CertificateHolder(signerCert.getEncoded()));

                CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
                generator.addSignerInfoGenerator(signerInfoGen);
                generator.addCertificates(new JcaCertStore(Arrays.asList(chain)));

                CMSSignedData signedData = generator.generate(new CMSProcessableByteArray(data), false);

                if (tsaEnabled) {
                    signedData = embedTimestamp(signedData);
                }
                return signedData.getEncoded();
            } catch (Exception e) {
                throw new IOException("CMS signature build failed: " + e.getMessage(), e);
            }
        }
    }

    /** Embed an RFC-3161 token over the signature value as the signature-time-stamp attribute. */
    private CMSSignedData embedTimestamp(CMSSignedData signedData) throws Exception {
        SignerInformation signer = signedData.getSignerInfos().getSigners().iterator().next();
        TimeStampToken token = tsaClient.timeStamp(signer.getSignature());

        ASN1EncodableVector unsigned = new ASN1EncodableVector();
        unsigned.add(new Attribute(
                PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                new DERSet(token.toCMSSignedData().toASN1Structure())));

        SignerInformation withTimestamp =
                SignerInformation.replaceUnsignedAttributes(signer, new AttributeTable(unsigned));
        return CMSSignedData.replaceSigners(signedData,
                new SignerInformationStore(Collections.singletonList(withTimestamp)));
    }
}
