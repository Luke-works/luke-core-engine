package com.luke.engine.capability.signature;

import java.io.ByteArrayInputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default {@link SigningKeyProvider}: loads an org document-signing key + cert chain from a
 * base64-encoded PKCS#12 ({@code LUKE_SIGN_KEYSTORE_BASE64} / {@code _PASSWORD} /
 * {@code LUKE_SIGN_KEY_ALIAS}). Loaded LAZILY on first use and cached, so the service boots
 * without a keystore configured (only signing requires it). See the README keytool note.
 */
@Component
public class KeystoreSigningKeyProvider implements SigningKeyProvider {

    private final String keystoreBase64;
    private final String password;
    private final String alias;

    private volatile PrivateKey privateKey;
    private volatile X509Certificate[] chain;

    public KeystoreSigningKeyProvider(
            @Value("${luke.sign.trust.keystore-base64:}") String keystoreBase64,
            @Value("${luke.sign.trust.keystore-password:}") String password,
            @Value("${luke.sign.trust.key-alias:}") String alias) {
        this.keystoreBase64 = keystoreBase64;
        this.password = password == null ? "" : password;
        this.alias = alias;
    }

    @Override
    public PrivateKey privateKey() {
        load();
        return privateKey;
    }

    @Override
    public X509Certificate[] certificateChain() {
        load();
        return chain;
    }

    private synchronized void load() {
        if (privateKey != null) return;
        if (keystoreBase64 == null || keystoreBase64.isBlank()) {
            throw new IllegalStateException(
                    "LUKE_SIGN_KEYSTORE_BASE64 not configured — set the org document-signing PKCS#12 "
                            + "(base64). See README 'Dev signing cert' for the keytool command.");
        }
        try {
            byte[] p12 = Base64.getDecoder().decode(keystoreBase64.trim());
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(p12), password.toCharArray());

            String useAlias = (alias != null && !alias.isBlank()) ? alias : ks.aliases().nextElement();
            Key key = ks.getKey(useAlias, password.toCharArray());
            if (!(key instanceof PrivateKey pk)) {
                throw new IllegalStateException("Alias '" + useAlias + "' has no private key");
            }
            Certificate[] certs = ks.getCertificateChain(useAlias);
            if (certs == null || certs.length == 0) {
                throw new IllegalStateException("Alias '" + useAlias + "' has no certificate chain");
            }
            List<X509Certificate> x509 = new ArrayList<>(certs.length);
            for (Certificate c : certs) x509.add((X509Certificate) c);

            this.chain = x509.toArray(new X509Certificate[0]);
            this.privateKey = pk; // set last: marks "loaded"
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load signing keystore: " + e.getMessage(), e);
        }
    }
}
