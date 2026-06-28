package com.luke.engine.capability.signature;

/**
 * Input to {@link SignatureProvider#stampAndSign}: the source PDF bytes, the drawn signature
 * PNG, the field to place it in, and the audit metadata for the Certificate of Completion.
 */
public record StampRequest(
        byte[] sourcePdf,
        byte[] signaturePng,
        Field field,
        AuditMeta meta
) {}
