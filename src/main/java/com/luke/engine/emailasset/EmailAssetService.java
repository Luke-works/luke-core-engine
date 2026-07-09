package com.luke.engine.emailasset;

import com.luke.engine.capability.access.CapabilityAccessService;
import com.luke.engine.emailasset.EmailAssetDtos.AuthorizeRequest;
import com.luke.engine.emailasset.EmailAssetDtos.AuthorizeResponse;
import com.luke.engine.emailasset.EmailAssetDtos.EmailAssetDto;
import com.luke.engine.emailasset.EmailAssetDtos.FinalizeRequest;
import com.luke.engine.emailasset.EmailAssetDtos.PublicResolveResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * The EMAIL ASSET state + authZ brain. luke-file-proxy calls this over the internal hop to authorize an
 * upload (→ assetId + storage key), finalize it, or PUBLICLY resolve it for serving. Bytes never reach
 * core. Upload is EMAIL-capability gated; the public resolve is un-gated (the unguessable assetId is the
 * bearer and email images are public by nature — only READY assets resolve).
 */
@Service
public class EmailAssetService {

    private static final String CAPABILITY = "EMAIL";

    private final EmailAssetRepository repo;
    private final CapabilityAccessService capabilities;

    public EmailAssetService(EmailAssetRepository repo, CapabilityAccessService capabilities) {
        this.repo = repo;
        this.capabilities = capabilities;
    }

    /** Authorize an upload: EMAIL-gate it, then create a PENDING row with a fresh assetId + storage key. */
    @Transactional
    public AuthorizeResponse authorize(String tenantId, String userId, String userName, AuthorizeRequest req) {
        require("filename", req.filename());
        require("contentType", req.contentType());
        if (!req.contentType().toLowerCase().startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "email assets must be images");
        }
        if (!capabilities.isAllowed(tenantId, userId, CAPABILITY, true)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no access to EMAIL capability");
        }

        EmailAsset a = new EmailAsset();
        a.setId(UUID.randomUUID().toString());
        a.setTenantId(tenantId);
        a.setTemplateId(blankToNull(req.templateId()));
        a.setFilename(req.filename());
        a.setContentType(req.contentType());
        a.setStatus(EmailAsset.STATUS_PENDING);
        a.setCreatedBy(blankToNull(userId));
        a.setCreatedByName(blankToNull(userName));
        // TENANT-RELATIVE key; BlobStore prepends {tenantId}/.
        // Physical S3 key = {tenant}/email-assets/{templateId}/{assetId}-{file}
        a.setStorageKey("email-assets/" + safeSegment(req.templateId()) + "/" + a.getId() + "-" + slug(req.filename()));
        repo.save(a);
        return new AuthorizeResponse(a.getId(), a.getStorageKey());
    }

    /** Finalize: the proxy reports the streamed size + checksum; flip PENDING → READY. */
    @Transactional
    public EmailAssetDto finalizeUpload(String tenantId, String assetId, FinalizeRequest req) {
        EmailAsset a = repo.findByIdAndTenantId(assetId, tenantId).orElseThrow(EmailAssetService::notFound);
        if (!EmailAsset.STATUS_PENDING.equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "asset already finalized");
        }
        a.setSizeBytes(req.sizeBytes());
        a.setSha256(req.sha256());
        a.setStatus(EmailAsset.STATUS_READY);
        repo.save(a);
        return EmailAssetDto.of(a);
    }

    /**
     * PUBLIC resolve for serving: by assetId ALONE — the unguessable id is the sole authorization, and
     * email assets are public by nature. Returns the tenantId + storage key so the (tenant-header-less)
     * public serve path can stream from S3. Only READY assets resolve; anything else is a 404.
     */
    @Transactional(readOnly = true)
    public PublicResolveResponse publicResolve(String assetId) {
        EmailAsset a = repo.findById(assetId)
                .filter(x -> EmailAsset.STATUS_READY.equals(x.getStatus()))
                .orElseThrow(EmailAssetService::notFound);
        return new PublicResolveResponse(a.getId(), a.getTenantId(), a.getStorageKey(), a.getContentType(), a.getFilename());
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private static void require(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    /** A single safe path segment for the storage key (templateId → sanitized, or "shared"). */
    private static String safeSegment(String s) {
        if (!StringUtils.hasText(s)) return "shared";
        String v = s.replaceAll("[^A-Za-z0-9._-]", "_");
        if (v.isBlank()) return "shared";
        return v.length() > 80 ? v.substring(0, 80) : v;
    }

    /** Filesystem/S3-safe filename for the key suffix. */
    private static String slug(String filename) {
        String base = filename == null ? "file" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank()) base = "file";
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "email asset not found");
    }
}
