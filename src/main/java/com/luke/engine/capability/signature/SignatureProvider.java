package com.luke.engine.capability.signature;

/**
 * SPI for the <b>product</b> layer we own: render the visual signature onto the PDF at the
 * field, append the Certificate of Completion (the IP-stamped audit trail), then hand the
 * prepared PDF to a {@link TrustProvider} for the cryptographic seal. Returns the final
 * sealed bytes. Implemented with PDFBox in SIG-2 ({@code NativeSignatureProvider}).
 */
public interface SignatureProvider {

    byte[] stampAndSign(StampRequest request);

    /**
     * Seal a MULTI-recipient instance: draw every recipient's signature/text at their fields,
     * append a multi-signer Certificate of Completion, then PAdES-seal once. Used at instance
     * closure (Phase 2). Returns the final sealed bytes.
     */
    byte[] sealEnvelope(EnvelopeSealRequest request);
}
