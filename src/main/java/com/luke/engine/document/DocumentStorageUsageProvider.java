package com.luke.engine.document;

import com.luke.engine.usage.StorageUsageProvider;
import org.springframework.stereotype.Component;

/**
 * The document registry's answer to "how many bytes does this tenant store?" — the concrete
 * {@link StorageUsageProvider} backing the {@code /api/usage} storage gauge. A single SUM over the
 * tenant's non-deleted {@link Document} rows (signed PDFs are registered here too), so it stays in
 * step with what the tenant actually occupies without a separate running total to keep consistent.
 */
@Component
public class DocumentStorageUsageProvider implements StorageUsageProvider {

    private final DocumentRepository documents;

    public DocumentStorageUsageProvider(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public long usedBytes(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return 0;
        return Math.max(0, documents.sumSizeBytesForTenant(tenantId));
    }
}
