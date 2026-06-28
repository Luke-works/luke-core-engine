package com.luke.engine.capability.signature;

import org.bouncycastle.tsp.TimeStampToken;

/**
 * Obtains an RFC-3161 trusted timestamp token over the signature value, embedded into the CMS
 * as the {@code signature-time-stamp} unsigned attribute. Default {@link HttpTsaClient} talks
 * to {@code LUKE_SIGN_TSA_URL}; a seam so a test can inject an in-process TSA and prod can
 * point at a paid/SLA (or AATL-CA) TSA.
 */
public interface TsaClient {

    /** Timestamp the given bytes (the SignerInfo signature value); returns the RFC-3161 token. */
    TimeStampToken timeStamp(byte[] signatureValue) throws Exception;
}
