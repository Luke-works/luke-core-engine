package com.luke.engine.emailasset;

import com.luke.engine.emailasset.EmailAssetDtos.AuthorizeRequest;
import com.luke.engine.emailasset.EmailAssetDtos.AuthorizeResponse;
import com.luke.engine.emailasset.EmailAssetDtos.EmailAssetDto;
import com.luke.engine.emailasset.EmailAssetDtos.FinalizeRequest;
import com.luke.engine.emailasset.EmailAssetDtos.PublicResolveResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-to-server EMAIL ASSET API for luke-file-proxy. Sits behind the InternalAuthFilter on
 * {@code /api/internal/**} (X-Internal-Key shared secret). The authenticated upload endpoints get the
 * gateway-asserted identity via {@code X-Tenant-Id}/{@code X-User-Id}; the public resolve takes no
 * identity — the unguessable assetId is the sole authorization (email assets are public).
 */
@RestController
@RequestMapping("/api/internal/email-assets")
public class InternalEmailAssetController {

    private final EmailAssetService service;

    public InternalEmailAssetController(EmailAssetService service) {
        this.service = service;
    }

    /** Authorize upload → { assetId, storageKey }. The proxy then streams bytes to that key. */
    @PostMapping("/authorize")
    public AuthorizeResponse authorize(@RequestHeader("X-Tenant-Id") String tenantId,
                                       @RequestHeader(value = "X-User-Id", required = false) String userId,
                                       @RequestHeader(value = "X-User-Name", required = false) String userName,
                                       @RequestBody AuthorizeRequest body) {
        requireTenant(tenantId);
        return service.authorize(tenantId, userId, userName, body);
    }

    /** Finalize after the proxy streamed the bytes (reports size + server-computed sha256). */
    @PostMapping("/{assetId}/finalize")
    public EmailAssetDto finalizeUpload(@RequestHeader("X-Tenant-Id") String tenantId,
                                        @PathVariable String assetId,
                                        @RequestBody FinalizeRequest body) {
        requireTenant(tenantId);
        return service.finalizeUpload(tenantId, assetId, body);
    }

    /** PUBLIC resolve (no tenant/identity — the assetId is the bearer) → { tenantId, storageKey, ... }.
     *  Called only by the file-proxy's public serve route to locate the S3 object. */
    @PostMapping("/public/{assetId}/resolve")
    public PublicResolveResponse publicResolve(@PathVariable String assetId) {
        return service.publicResolve(assetId);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
