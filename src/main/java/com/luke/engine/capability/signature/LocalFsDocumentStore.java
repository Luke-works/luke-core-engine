package com.luke.engine.capability.signature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev/default {@link DocumentStore}: writes objects to the local filesystem under
 * {@code LUKE_DOCSTORE_LOCAL_DIR/<tenantId>/<key>}. Selected when
 * {@code LUKE_DOCSTORE_PROVIDER=local} (the default). Zero external infra.
 *
 * <p>Keep-on-merge: at SIG-M this travels into core unchanged and remains the dev store; the
 * S3 impl becomes the prod default. Tenant + key are path-segment-validated to prevent any
 * traversal outside the configured root.
 */
@Component
@ConditionalOnProperty(name = "luke.docstore.provider", havingValue = "local", matchIfMissing = true)
public class LocalFsDocumentStore implements DocumentStore {

    private final Path root;

    public LocalFsDocumentStore(@Value("${luke.docstore.local-dir:./data/docstore}") String localDir) {
        this.root = Paths.get(localDir).toAbsolutePath().normalize();
    }

    @Override
    public String put(String tenantId, String key, byte[] bytes, String contentType) {
        Path target = resolve(tenantId, key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("DocumentStore put failed for " + key, e);
        }
        return key;
    }

    @Override
    public byte[] get(String tenantId, String key) {
        Path target = resolve(tenantId, key);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("DocumentStore get failed for " + key, e);
        }
    }

    @Override
    public void delete(String tenantId, String key) {
        try {
            Files.deleteIfExists(resolve(tenantId, key));
        } catch (IOException e) {
            throw new UncheckedIOException("DocumentStore delete failed for " + key, e);
        }
    }

    /** Resolve (tenantId, key) under the root, refusing any path that escapes it. */
    private Path resolve(String tenantId, String key) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId required");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
        Path resolved = root.resolve(tenantId).resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Illegal object key (path traversal): " + key);
        }
        return resolved;
    }
}
