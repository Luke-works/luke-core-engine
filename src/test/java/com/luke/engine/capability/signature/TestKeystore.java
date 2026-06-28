package com.luke.engine.capability.signature;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Generates a throwaway PKCS#12 (alias luke-sign / password changeit) for sealing in tests. */
final class TestKeystore {

    static final String ALIAS = "luke-sign";
    static final String PASSWORD = "changeit";

    private TestKeystore() {}

    static String base64() {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            long now = System.currentTimeMillis();
            long day = 86_400_000L;
            X500Name dn = new X500Name("CN=Luke Signature Test, O=Luke, C=US");
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    dn, BigInteger.valueOf(now), new Date(now - day), new Date(now + 3650L * day), dn, kp.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(ALIAS, kp.getPrivate(), PASSWORD.toCharArray(), new Certificate[]{cert});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ks.store(out, PASSWORD.toCharArray());
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("test keystore generation failed", e);
        }
    }
}
