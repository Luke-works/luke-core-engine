package com.luke.engine.emailasset;

/**
 * Wire records for the internal email-asset API (called by luke-file-proxy over the shared-secret hop).
 * Package-private — they cross only the internal hop, never the browser.
 */
final class EmailAssetDtos {
    private EmailAssetDtos() {}

    /** authorize: the proxy hands the upload's context; core creates a PENDING row + storage key. */
    record AuthorizeRequest(String templateId, String filename, String contentType) {}

    /** authorize response: assetId + TENANT-RELATIVE storage key (BlobStore prepends tenantId). */
    record AuthorizeResponse(String assetId, String storageKey) {}

    /** finalize: the proxy reports the streamed size + server-computed checksum. */
    record FinalizeRequest(Long sizeBytes, String sha256) {}

    /** Metadata returned after finalize. */
    record EmailAssetDto(String assetId, String templateId, String filename, String contentType,
                         Long sizeBytes, String status) {
        static EmailAssetDto of(EmailAsset a) {
            return new EmailAssetDto(a.getId(), a.getTemplateId(), a.getFilename(), a.getContentType(),
                    a.getSizeBytes(), a.getStatus());
        }
    }

    /** Public resolve: carries the asset's tenantId + storageKey so the (tenant-header-less) public
     *  serve path can build the physical S3 key. Mirrors the embed PublicAuthorizeResponse pattern. */
    record PublicResolveResponse(String assetId, String tenantId, String storageKey,
                                 String contentType, String filename) {}
}
