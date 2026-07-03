package com.luke.engine.capability.signature;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Supplies the document-signing key material for the PAdES seal. Default
 * {@link KeystoreSigningKeyProvider} loads an org PKCS#12 from {@code LUKE_SIGN_KEYSTORE_*}.
 * A seam so prod can swap in an AATL-member CA cert (for Adobe trust) or an HSM/KMS-backed key
 * without touching {@link SelfSealTrustProvider}.
 */
public interface SigningKeyProvider {

    PrivateKey privateKey();

    /** Signer cert first, then any chain certs. */
    X509Certificate[] certificateChain();
}
