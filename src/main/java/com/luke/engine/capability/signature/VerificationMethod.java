package com.luke.engine.capability.signature;

/**
 * How a signer must prove control of the contact the REQUESTER specified before signing.
 * Set by the requester at create time and IMMUTABLE by the signer (self-provided contact
 * would be theater). Stored as a string (@Enumerated STRING) — DB-portable.
 *
 * <p>V1 ships {@code NONE}. {@code EMAIL_OTP} (sender-asserted email) lands at SIG-9;
 * {@code SMS_OTP} (requester-provided phone + paid gateway) and {@code IDV} (government ID
 * + liveness) are higher tiers behind the same {@link SignerVerification} seam. OTP proves
 * CONTACT CONTROL, not identity — identity needs the IDV tier.
 */
public enum VerificationMethod {
    NONE,
    EMAIL_OTP,
    SMS_OTP,
    IDV
}
