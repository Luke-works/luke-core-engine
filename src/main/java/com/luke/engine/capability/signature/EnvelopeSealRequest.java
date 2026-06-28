package com.luke.engine.capability.signature;

import java.util.List;

/**
 * Input to {@link SignatureProvider#sealEnvelope}: seal a MULTI-recipient instance into one final
 * PAdES document. Each {@link FieldStamp} draws either a signature PNG (SIGNATURE/INITIALS) or a
 * line of text (DATE/NAME) at its field; the certificate lists every signer. Used at instance
 * closure (mirrors the single-field {@link StampRequest} path, generalised to many fields/signers).
 */
public record EnvelopeSealRequest(byte[] sourcePdf, List<FieldStamp> stamps, EnvelopeMeta meta) {

    /** One placement: a PNG image (when {@code png != null}) or a text value, at {@code field}. */
    public record FieldStamp(Field field, byte[] png, String text) {
        public static FieldStamp image(Field field, byte[] png) {
            return new FieldStamp(field, png, null);
        }
        public static FieldStamp text(Field field, String text) {
            return new FieldStamp(field, null, text);
        }
    }

    public record EnvelopeMeta(String name, String sourceSha256, List<SignerSummary> signers) {}

    public record SignerSummary(String name, String email, String signedAt) {}
}
