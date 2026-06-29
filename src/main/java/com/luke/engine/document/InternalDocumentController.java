package com.luke.engine.document;

import com.luke.engine.document.DocumentDtos.AuthorizeRequest;
import com.luke.engine.document.DocumentDtos.AuthorizeResponse;
import com.luke.engine.document.DocumentDtos.DocumentDto;
import com.luke.engine.document.DocumentDtos.FinalizeRequest;
import com.luke.engine.document.DocumentDtos.LinkProcessRequest;
import com.luke.engine.document.DocumentDtos.ResolveResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-to-server documents API for luke-file-proxy. Sits behind the shared-secret
 * {@link com.luke.engine.capability.access.InternalAuthFilter} on {@code /api/internal/**}; identity is
 * asserted by the gateway and relayed by the proxy as {@code X-Tenant-Id} / {@code X-User-Id}.
 * Bytes never reach this controller — only the small structured state of the upload/download.
 */
@RestController
@RequestMapping("/api/internal/documents")
public class InternalDocumentController {

    private final DocumentService docs;

    public InternalDocumentController(DocumentService docs) {
        this.docs = docs;
    }

    /** Authorize an upload → { docId, storageKey }. The proxy then streams bytes to that key. */
    @PostMapping("/authorize")
    public AuthorizeResponse authorize(@RequestHeader("X-Tenant-Id") String tenantId,
                                       @RequestHeader(value = "X-User-Id", required = false) String userId,
                                       @RequestHeader(value = "X-User-Name", required = false) String userName,
                                       @RequestBody AuthorizeRequest body) {
        requireTenant(tenantId);
        return docs.authorize(tenantId, userId, userName, body);
    }

    /** Finalize after the proxy streamed the bytes (reports size + server-computed sha256). */
    @PostMapping("/{docId}/finalize")
    public DocumentDto finalizeUpload(@RequestHeader("X-Tenant-Id") String tenantId,
                                      @PathVariable String docId,
                                      @RequestBody FinalizeRequest body) {
        requireTenant(tenantId);
        return docs.finalizeUpload(tenantId, docId, body);
    }

    /** Flow-A backfill (DOC-9): stamp processInstanceId onto every row for processRef → { linked }. */
    @PostMapping("/process-started")
    public Map<String, Integer> processStarted(@RequestHeader("X-Tenant-Id") String tenantId,
                                               @RequestBody LinkProcessRequest body) {
        requireTenant(tenantId);
        int linked = docs.linkProcessInstance(tenantId, body.processRef(), body.processInstanceId());
        return Map.of("linked", linked);
    }

    /** Resolve a doc for download → { storageKey, contentType, filename } (gated). */
    @PostMapping("/{docId}/resolve")
    public ResolveResponse resolve(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestHeader(value = "X-User-Id", required = false) String userId,
                                   @PathVariable String docId) {
        requireTenant(tenantId);
        return docs.resolveForDownload(tenantId, userId, docId);
    }

    /** Doc metadata (gated). */
    @GetMapping("/{docId}")
    public DocumentDto metadata(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @PathVariable String docId) {
        requireTenant(tenantId);
        return docs.metadata(tenantId, userId, docId);
    }

    /** List a case file / task / capability-owner (filtered to what the caller may see). */
    @GetMapping
    public List<DocumentDto> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @RequestParam(required = false) String processRef,
                                  @RequestParam(required = false) String taskId,
                                  @RequestParam(required = false) String capability,
                                  @RequestParam(required = false) String ownerEntityId) {
        requireTenant(tenantId);
        return docs.list(tenantId, userId, processRef, taskId, capability, ownerEntityId);
    }

    /** Soft-delete (gated + retention-checked) → { storageKey } so the proxy drops the S3 object. */
    @DeleteMapping("/{docId}")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> delete(@RequestHeader("X-Tenant-Id") String tenantId,
                                      @RequestHeader(value = "X-User-Id", required = false) String userId,
                                      @PathVariable String docId) {
        requireTenant(tenantId);
        return Map.of("storageKey", docs.delete(tenantId, userId, docId));
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
