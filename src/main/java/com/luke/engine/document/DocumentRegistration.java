package com.luke.engine.document;

import java.time.LocalDateTime;

/**
 * Registers an ALREADY-STORED blob in the shared document index (DOC-7). Unlike the upload flow
 * (authorize → finalize), the bytes already exist in their owning capability's store and the write was
 * already authorized by that capability's own flow — so this skips the upload authZ gate and lands a
 * READY {@link Document} row directly, keyed (and idempotent) on {@code (tenantId, storageKey)}.
 *
 * <p>Used by SIGNATURES to surface its source/signed PDFs in the unified registry. The {@code storageKey}
 * is the capability store's TENANT-RELATIVE key. Under {@code luke.docstore.provider=s3} (DOC-7 byte-path
 * unification) the signature {@code S3DocumentStore} writes to the SAME bucket + {@code {tenantId}/{key}}
 * scheme the proxy reads, so {@code GET /api/documents/{id}/content} resolves these rows uniformly. Under
 * the local dev store the bytes live on core's disk, so {@code /content} won't resolve there — signatures
 * keep serving via their own {@code signed.pdf} endpoint either way.
 *
 * @param processRef  case-file folder — the process businessKey, or the capability entity's stable code
 * @param ownerEntityId the owning capability entity (signatureRequestId / instanceId)
 */
public record DocumentRegistration(
        String tenantId,
        String processRef,
        String processInstanceId,
        String taskId,
        String kind,
        String capability,
        String ownerEntityId,
        String storageKey,
        String filename,
        String contentType,
        Long sizeBytes,
        String sha256,
        LocalDateTime retainUntil,
        String createdBy,
        String createdByName) {
}
