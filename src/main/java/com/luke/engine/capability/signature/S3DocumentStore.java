package com.luke.engine.capability.signature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * Prod {@link DocumentStore} over AWS S3 (DOC-7 byte-path unification). Selected when
 * {@code luke.docstore.provider=s3} — at which point {@code LocalFsDocumentStore} (matchIfMissing) steps
 * aside and signature source/signed/sealed PDFs land in the SAME bucket luke-file-proxy reads, under the
 * SAME {@code {tenantId}/{key}} scheme. That makes a signature's registered {@link com.luke.engine.document.Document}
 * row resolvable by the proxy's {@code GET /api/documents/{id}/content} — one uniform download path — and
 * ends ephemeral local-disk storage in prod.
 *
 * <p>Signatures already buffer the whole (≤25&nbsp;MiB) PDF in heap for PDFBox sealing, so this byte[]
 * put/get adds no streaming load to core's request threads — the general large-file streaming path stays
 * in the proxy. Bytes still NEVER hit Postgres or Camunda.
 */
@Component
@ConditionalOnProperty(name = "luke.docstore.provider", havingValue = "s3")
public class S3DocumentStore implements DocumentStore {

    private final S3Client s3;
    private final String bucket;
    private final String kmsKey;

    public S3DocumentStore(S3Client documentStoreS3Client,
                           @Value("${luke.docstore.bucket:}") String bucket,
                           @Value("${luke.docstore.kms-key:}") String kmsKey) {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("luke.docstore.bucket must be set when provider=s3");
        }
        this.s3 = documentStoreS3Client;
        this.bucket = bucket;
        this.kmsKey = kmsKey;
    }

    @Override
    public String put(String tenantId, String key, byte[] bytes, String contentType) {
        String objectKey = objectKey(tenantId, key);
        PutObjectRequest.Builder req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentLength((long) bytes.length);
        if (StringUtils.hasText(contentType)) {
            req.contentType(contentType);
        }
        if (StringUtils.hasText(kmsKey)) {
            req.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKey);
        }
        s3.putObject(req.build(), RequestBody.fromBytes(bytes));
        return key;
    }

    @Override
    public byte[] get(String tenantId, String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(tenantId, key))
                .build()).asByteArray();
    }

    @Override
    public void delete(String tenantId, String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(tenantId, key))
                .build());
    }

    /** Physical key {@code {tenantId}/{key}} — same scheme the proxy uses, validated against traversal. */
    private static String objectKey(String tenantId, String key) {
        validate("tenantId", tenantId);
        validate("key", key);
        return tenantId + "/" + key;
    }

    private static void validate(String label, String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        if (segment.startsWith("/") || segment.contains("..") || segment.contains("\\")) {
            throw new IllegalArgumentException("illegal " + label + " (traversal): " + segment);
        }
    }
}
