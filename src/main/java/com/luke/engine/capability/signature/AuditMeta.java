package com.luke.engine.capability.signature;

import java.util.List;

/**
 * Everything the signing engine needs to render the Certificate of Completion (SIG-2):
 * the signer identity, the SHA-256 of the ORIGINAL source PDF, and the ordered IP-stamped
 * event trail (created/sent/viewed/signed — each with time, IP, ipRisk, user-agent, actor).
 */
public record AuditMeta(
        String signerName,
        String signerEmail,
        String sourceSha256,
        List<SignatureAuditEvent> events
) {}
