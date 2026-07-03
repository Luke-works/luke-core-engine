package com.luke.engine.document;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Wire records for the internal documents API (called by luke-file-proxy). Kept package-private —
 * they cross only the internal hop, never the browser. Timestamps are epoch-ms (mirrors the
 * forms/email/signatures adapter convention) even though the entity stores {@link LocalDateTime}.
 */
final class DocumentDtos {
    private DocumentDtos() {}

    /** authorize: the proxy hands the upload's context; core creates a PENDING row + storage key.
     *  {@code retainUntilMs} is an optional caller-supplied retention horizon (e.g. SIGNATURES); when
     *  null the per-capability default applies ({@link DocumentRetentionPolicy}). */
    record AuthorizeRequest(
            String processRef, String taskId, String kind, String capability,
            String ownerEntityId, String filename, String contentType, Long retainUntilMs) {

        /** Back-compat 7-arg form (no explicit retention) for existing callers/tests. */
        AuthorizeRequest(String processRef, String taskId, String kind, String capability,
                         String ownerEntityId, String filename, String contentType) {
            this(processRef, taskId, kind, capability, ownerEntityId, filename, contentType, null);
        }
    }

    /** authorize response: docId + TENANT-RELATIVE storage key (BlobStore prepends tenantId), plus the
     *  retention the proxy must stamp as S3 Object Lock on PUT ({@code retainUntilMs}/{@code objectLockMode}
     *  null = no lock; DOC-5). */
    record AuthorizeResponse(String docId, String storageKey, Long retainUntilMs, String objectLockMode) {}

    /** finalize: the proxy reports the streamed size + server-computed checksum. */
    record FinalizeRequest(Long sizeBytes, String sha256) {}

    /** process-started (Flow-A backfill, DOC-9): stamp processInstanceId onto a processRef's rows. */
    record LinkProcessRequest(String processRef, String processInstanceId) {}

    // ── public (embed-token) flow: the token carries the (unforgeable) tenant; no identity headers ──
    record PublicAuthorizeRequest(String token, String processRef, String filename, String contentType) {}

    record PublicFinalizeRequest(String token, Long sizeBytes, String sha256) {}

    record PublicLinkRequest(String token, String processRef, String instanceId) {}

    /** Public authorize response: like {@link AuthorizeResponse} but ALSO carries the token-derived
     *  tenantId, since the proxy (which has no session tenant on this path) needs it to build the S3 key. */
    record PublicAuthorizeResponse(String docId, String tenantId, String storageKey,
                                   Long retainUntilMs, String objectLockMode) {}

    /** Authenticated delete result: the storage key to drop, plus whether the proxy should HARD-delete
     *  (purge every S3 version) rather than add a delete marker. True when the doc was never under
     *  retention — a removed attachment is then gone immediately, not retained as a noncurrent version. */
    record DropResult(String storageKey, boolean hardDelete) {}

    /** Public delete result: like {@link DropResult} but also carries the (token-derived) tenantId the
     *  proxy needs to address the right physical object. */
    record PublicDropResult(String tenantId, String storageKey, boolean hardDelete) {}

    /** resolve (download): core returns just what the proxy needs to stream the bytes back. */
    record ResolveResponse(String docId, String storageKey, String contentType, String filename, String status) {}

    /** Client-safe view of a document. NEVER includes storageKey. */
    record DocumentDto(
            String docId, String processRef, String processInstanceId, String taskId,
            String kind, String capability, String ownerEntityId,
            String filename, String contentType, Long sizeBytes, String sha256,
            String status, Long retainUntil, String createdBy, String createdByName, Long createdAt) {

        static DocumentDto of(Document d) {
            return new DocumentDto(
                    d.getId(), d.getProcessRef(), d.getProcessInstanceId(), d.getTaskId(),
                    d.getKind(), d.getCapability(), d.getOwnerEntityId(),
                    d.getFilename(), d.getContentType(), d.getSizeBytes(), d.getSha256(),
                    d.getStatus(), ms(d.getRetainUntil()), d.getCreatedBy(), d.getCreatedByName(),
                    ms(d.getCreatedAt()));
        }

        private static Long ms(LocalDateTime t) {
            return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
    }
}
