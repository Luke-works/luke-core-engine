package com.luke.engine.document;

import com.luke.engine.document.DocumentDtos.DocumentDto;
import com.luke.engine.document.DocumentDtos.FinalizeRequest;
import com.luke.engine.document.DocumentDtos.PublicAuthorizeRequest;
import com.luke.engine.document.DocumentDtos.PublicAuthorizeResponse;
import com.luke.engine.document.DocumentDtos.PublicDropResult;
import com.luke.engine.document.DocumentDtos.PublicFinalizeRequest;
import com.luke.engine.document.DocumentDtos.PublicLinkRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-to-server endpoints for the PUBLIC (embed-token) document flow, called by luke-file-proxy over
 * the shared-secret internal hop ({@link com.luke.engine.capability.access.InternalAuthFilter} guards
 * {@code /api/internal/**}). The embed token travels in the request (verified here →
 * {@link EmbedDocumentService}); there is NO identity header — the token is the only authorization, and
 * the tenant it resolves to is unforgeable. Bytes never reach this controller.
 */
@RestController
@RequestMapping("/api/internal/documents/public")
public class InternalEmbedDocumentController {

    private final EmbedDocumentService embed;

    public InternalEmbedDocumentController(EmbedDocumentService embed) {
        this.embed = embed;
    }

    /** Authorize an embed upload → { docId, tenantId, storageKey, retention }. */
    @PostMapping("/authorize")
    public PublicAuthorizeResponse authorize(@RequestBody PublicAuthorizeRequest body) {
        return embed.authorize(body.token(), body.processRef(), body.filename(), body.contentType());
    }

    /** Finalize after the proxy streamed the bytes. */
    @PostMapping("/{docId}/finalize")
    public DocumentDto finalizeUpload(@PathVariable String docId, @RequestBody PublicFinalizeRequest body) {
        return embed.finalizeUpload(body.token(), docId, new FinalizeRequest(body.sizeBytes(), body.sha256()));
    }

    /** List this session's attachments (token tenant + processRef). */
    @GetMapping
    public List<DocumentDto> list(@RequestParam String token, @RequestParam String processRef) {
        return embed.list(token, processRef);
    }

    /** Remove one attachment before submit → { storageKey } so the proxy drops the bytes. */
    @DeleteMapping("/{docId}")
    public Map<String, String> delete(@PathVariable String docId,
                                      @RequestParam String token,
                                      @RequestParam String processRef) {
        PublicDropResult r = embed.delete(token, processRef, docId);
        return Map.of("tenantId", r.tenantId(), "storageKey", r.storageKey());
    }

    /** Bind a session's uploads to the form instance created at submit → { linked }. */
    @PostMapping("/link")
    public Map<String, Integer> link(@RequestBody PublicLinkRequest body) {
        return Map.of("linked", embed.link(body.token(), body.processRef(), body.instanceId()));
    }
}
