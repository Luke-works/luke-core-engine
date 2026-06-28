package com.luke.engine.capability.signature;

/**
 * Pluggable object storage for source + signed PDFs. The bytes NEVER live in the database;
 * {@link SignatureRequest} keeps only the returned object keys.
 *
 * <p>Key scheme (tenant prefix handled by the store): {@code signatures/{requestId}/source.pdf}
 * and {@code signatures/{requestId}/signed.pdf}.
 *
 * <p>V1 default = {@link LocalFsDocumentStore} (local filesystem). Prod swaps in an S3 impl
 * (AWS S3 / Cloudflare R2 / Backblaze B2 / a MinIO endpoint) via {@code LUKE_DOCSTORE_PROVIDER=s3}
 * — same interface, config-only change.
 */
public interface DocumentStore {

    /** Store bytes under (tenantId, key); returns the canonical object key to persist. */
    String put(String tenantId, String key, byte[] bytes, String contentType);

    /** Fetch the bytes for (tenantId, key). Throws if absent. */
    byte[] get(String tenantId, String key);

    /** Delete (tenantId, key) if present (idempotent) — used by the retention purge (SIG-8). */
    void delete(String tenantId, String key);
}
