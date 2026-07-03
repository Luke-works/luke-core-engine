package com.luke.engine.capability.signature;

/**
 * SPI for proving the signer controls the contact the REQUESTER specified, before signing.
 * V1 ships {@link NoneSignerVerification} (no challenge). SIG-9 adds
 * {@code EmailOtpSignerVerification} (sender-asserted email OTP); SMS OTP + IDV are higher
 * tiers behind this same seam.
 *
 * <p>INVARIANT: the contact ({@code signerEmail}/{@code signerPhone}) is whatever the requester
 * set on the {@link SignatureRequest}; the signer can never change it. That is what makes a
 * challenge meaningful — it proves CONTACT CONTROL (not identity).
 */
public interface SignerVerification {

    /** Whether this request demands verification before the sign POST is accepted. */
    boolean required(SignatureRequest request);

    /** Issue a challenge (e.g. send an OTP to the requester-set contact). No-op when not required. */
    void challenge(SignatureRequest request);

    /** Validate a submitted code against the active challenge. True when verification passes. */
    boolean verify(SignatureRequest request, String code);
}
