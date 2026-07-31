package com.luke.engine.document;

import com.luke.engine.capability.form.EmbedFormResolver;
import com.luke.engine.document.DocumentDtos.AuthorizeResponse;
import com.luke.engine.document.DocumentDtos.DocumentDto;
import com.luke.engine.document.DocumentDtos.FinalizeRequest;
import com.luke.engine.document.DocumentDtos.PublicAuthorizeResponse;
import com.luke.engine.document.DocumentDtos.PublicDropResult;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public, embed-token-scoped document flow (attachments on a PUBLIC embedded form). The embed token —
 * verified by {@link EmbedFormResolver} — yields the unforgeable tenant; the client-minted
 * {@code processRef} (high-entropy, Flow-A) is the case-file folder these uploads share until the form
 * is submitted (then bound to the instance via {@link #link}). Capability is always FORMS /
 * FORM_ATTACHMENT. No session user / capability / candidate-group gate — the token IS the authorization.
 *
 * <p>Abuse guards for this UNAUTHENTICATED write path: per-token upload rate limit + a hard per-session
 * attachment count cap (size is capped at 25&nbsp;MiB by the proxy). Bytes never reach core.
 */
@Service
public class EmbedDocumentService {

    private static final String CAPABILITY = "FORMS";
    private static final int MAX_UPLOADS_PER_TOKEN_PER_MIN = 30;
    private static final int MAX_ATTACHMENTS_PER_PROCESS = 20;

    private final DocumentService documents;
    private final EmbedFormResolver resolver;
    private final com.luke.engine.branding.PlanFeatures planFeatures;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public EmbedDocumentService(DocumentService documents, EmbedFormResolver resolver,
                                com.luke.engine.branding.PlanFeatures planFeatures) {
        this.documents = documents;
        this.resolver = resolver;
        this.planFeatures = planFeatures;
    }

    /** Authorize an upload for a valid embed token's tenant under the client's processRef. */
    public PublicAuthorizeResponse authorize(String token, String processRef, String filename, String contentType) {
        String tenantId = resolver.resolveTenant(token);
        // Attachments are a PAID feature, and this is the boundary that matters: the embed payload
        // already hides the tab for a free tenant, but the browser is not the gate — this endpoint is
        // reachable by anyone holding the token. Refused BEFORE the rate-limit window is touched, so a
        // free tenant's blocked uploads can't consume a paying tenant's budget on a shared token.
        if (!planFeatures.canUseAttachments(tenantId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "File attachments are available on paid plans.");
        }
        rateLimit("t:" + token);
        if (documents.countActiveAnonymous(tenantId, processRef) >= MAX_ATTACHMENTS_PER_PROCESS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Attachment limit reached for this form (" + MAX_ATTACHMENTS_PER_PROCESS + ").");
        }
        AuthorizeResponse a = documents.authorizeAnonymous(tenantId, CAPABILITY, Document.KIND_FORM_ATTACHMENT,
                processRef, filename, contentType);
        // Carry the (token-derived) tenant out so the proxy can build the physical S3 key.
        return new PublicAuthorizeResponse(a.docId(), tenantId, a.storageKey(), a.retainUntilMs(), a.objectLockMode());
    }

    /** Finalize after the proxy streamed the bytes (size + server-computed sha256), scoped to the token's tenant. */
    public DocumentDto finalizeUpload(String token, String docId, FinalizeRequest req) {
        String tenantId = resolver.resolveTenant(token);
        return documents.finalizeUpload(tenantId, docId, req);
    }

    /** List this session's attachments (token tenant + processRef). */
    public List<DocumentDto> list(String token, String processRef) {
        String tenantId = resolver.resolveTenant(token);
        return documents.listAnonymous(tenantId, processRef);
    }

    /** Remove one attachment before submit (token tenant + processRef); returns tenant + key + the
     *  hard-delete signal so the proxy purges the bytes from S3 immediately (not a noncurrent version). */
    public PublicDropResult delete(String token, String processRef, String docId) {
        String tenantId = resolver.resolveTenant(token);
        DocumentDtos.DropResult drop = documents.deleteAnonymous(tenantId, processRef, docId);
        return new PublicDropResult(tenantId, drop.storageKey(), drop.hardDelete());
    }

    /** Bind this session's uploads to the form instance created at submit. */
    public int link(String token, String processRef, String instanceId) {
        String tenantId = resolver.resolveTenant(token);
        return documents.linkToInstance(tenantId, processRef, instanceId);
    }

    // ── per-token upload rate limit (fixed 1-minute window) ──────────────────────
    private void rateLimit(String key) {
        long minute = System.currentTimeMillis() / 60_000L;
        Window w = windows.compute(key, (k, cur) ->
                (cur == null || cur.minute != minute) ? new Window(minute) : cur);
        if (w.count.incrementAndGet() > MAX_UPLOADS_PER_TOKEN_PER_MIN) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many uploads, try again shortly.");
        }
        if (windows.size() > 50_000) {
            windows.values().removeIf(win -> win.minute != minute);
        }
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger(0);
        Window(long minute) { this.minute = minute; }
    }
}
