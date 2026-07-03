package com.luke.engine.capability.signature;

/**
 * Metadata for the cryptographic PAdES seal applied by {@link TrustProvider#seal}
 * (signer identity + the standard signature reason/location fields).
 */
public record SealMeta(
        String signerName,
        String signerEmail,
        String reason,
        String location
) {}
