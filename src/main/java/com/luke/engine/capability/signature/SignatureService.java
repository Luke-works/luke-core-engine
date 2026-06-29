package com.luke.engine.capability.signature;

import com.luke.engine.capability.signature.SignatureSupport.Action;
import com.luke.engine.capability.signature.SignatureSupport.Status;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates the signature lifecycle so the controllers stay thin: persistence (tenant-scoped),
 * the {@link DocumentStore} (source + signed PDFs, never DB blobs), {@code retainUntil}, the
 * IP-stamped {@link AuditService} at every step, the opt-in IP-risk block policy, and the
 * stamp→seal handoff to {@link SignatureProvider}.
 */
@Service
public class SignatureService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SignatureService.class);

    private final SignatureRequestRepository requests;
    private final SignatureAuditEventRepository audits;
    private final AuditService auditService;
    private final DocumentStore documentStore;
    private final SignatureProvider signatureProvider;
    private final SignerVerification signerVerification;
    private final IpReputationProvider ipReputation;
    private final com.luke.engine.document.DocumentService documents;

    private final long retentionDays;
    private final String publicBaseUrl;
    private final Set<IpRisk> blockedRisks;
    private final TransactionTemplate txTemplate;

    public SignatureService(SignatureRequestRepository requests,
                            SignatureAuditEventRepository audits,
                            AuditService auditService,
                            DocumentStore documentStore,
                            SignatureProvider signatureProvider,
                            SignerVerification signerVerification,
                            IpReputationProvider ipReputation,
                            com.luke.engine.document.DocumentService documents,
                            PlatformTransactionManager txManager,
                            @Value("${luke.sign.retention-days:2555}") long retentionDays,
                            @Value("${luke.sign.public-base-url:http://localhost:5173}") String publicBaseUrl,
                            @Value("${luke.sign.block-ip-risk:}") String blockIpRiskCsv) {
        this.requests = requests;
        this.audits = audits;
        this.auditService = auditService;
        this.documentStore = documentStore;
        this.signatureProvider = signatureProvider;
        this.signerVerification = signerVerification;
        this.ipReputation = ipReputation;
        this.documents = documents;
        this.txTemplate = new TransactionTemplate(txManager);
        this.retentionDays = retentionDays;
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        this.blockedRisks = parseRisks(blockIpRiskCsv);
    }

    /**
     * DOC-7: surface a signature PDF in the shared document index, best-effort. Runs in its own
     * transaction (REQUIRES_NEW) and is fully swallowed on error — the signature flow is the source of
     * truth and must never fail because the registry mirror did.
     */
    private void registerSignatureDoc(SignatureRequest req, String storageKey, String filename,
                                      Long sizeBytes, String sha256) {
        try {
            documents.registerStored(new com.luke.engine.document.DocumentRegistration(
                    req.getTenantId(),
                    req.getCode(),                 // processRef = the stable signature code (case-file folder)
                    null, null,
                    com.luke.engine.document.Document.KIND_SIGNATURE_ATTACHMENT,
                    "SIGNATURES",
                    req.getId(),                   // ownerEntityId = signatureRequestId
                    storageKey, filename, "application/pdf",
                    sizeBytes, sha256,
                    req.getRetainUntil(),
                    req.getCreatedBy(), null));
        } catch (RuntimeException e) {
            log.debug("Document registry mirror failed for signature {} ({}): {}",
                    req.getId(), filename, e.toString());
        }
    }

    // ── Authenticated (tenant-scoped) ──────────────────────────────────────────────

    /** Create a DRAFT: store the source PDF, capture hash/size/retainUntil, audit CREATED. */
    @Transactional
    public SignatureRequest create(String tenantId, String userId, SignatureDraft draft,
                                   byte[] pdf, HttpServletRequest http) {
        SignatureRequest req = new SignatureRequest();
        req.setTenantId(tenantId);
        req.setName(draft.name());
        req.setSignerEmail(draft.signerEmail());
        req.setSignerName(draft.signerName());
        req.setSignerPhone(blankToNull(draft.signerPhone()));
        req.setVerificationMethod(draft.verificationMethod() == null
                ? VerificationMethod.NONE : draft.verificationMethod());
        Field f = draft.field();
        req.setFieldPage(f.page());
        req.setFieldX(f.x());
        req.setFieldY(f.y());
        req.setFieldW(f.w());
        req.setFieldH(f.h());
        req.setStatus(Status.DRAFT);
        req.setCreatedBy(userId);
        req.setCode(uniqueCode(tenantId));
        req.setSourceSha256(SignatureSupport.sha256Hex(pdf));
        req.setSizeBytes((long) pdf.length);
        req.setRetainUntil(LocalDateTime.now().plusDays(retentionDays));

        req = requests.save(req); // assigns id
        String key = sourceKey(req.getId());
        documentStore.put(tenantId, key, pdf, "application/pdf");
        req.setSourceObjectKey(key);
        req = requests.save(req);

        registerSignatureDoc(req, key, "source.pdf", req.getSizeBytes(), req.getSourceSha256());
        auditService.record(req, Action.CREATED, userId, http);
        return req;
    }

    public List<SignatureRequest> list(String tenantId) {
        return requests.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public SignatureRequest get(String tenantId, String id) {
        return load(tenantId, id);
    }

    /** Authed read path: tenant-scoped at the data layer (not by caller convention). */
    public List<SignatureAuditEvent> auditTrail(String tenantId, String requestId) {
        return audits.findByRequestIdAndTenantIdOrderByAtAsc(requestId, tenantId);
    }

    /** Token-authenticated path (the request is already resolved by its sign token). */
    public List<SignatureAuditEvent> auditTrail(String requestId) {
        return audits.findByRequestIdOrderByAtAsc(requestId);
    }

    /** Mint the public sign token (idempotent), flip to SENT, audit, return the signing URL. */
    @Transactional
    public String send(String tenantId, String id, HttpServletRequest http) {
        SignatureRequest req = load(tenantId, id);
        if (Status.VOIDED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot send a voided request");
        }
        if (Status.COMPLETED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is already completed");
        }
        // Idempotent re-send (e.g. "copy link"): a token already exists → return the existing link
        // WITHOUT bumping sentAt or recording another SENT event. Only the first send mutates.
        if (req.getSignToken() != null) {
            return publicBaseUrl + "/sign/" + req.getSignToken();
        }
        req.setSignToken(SignatureSupport.generateSignToken());
        req.setStatus(Status.SENT);
        req.setSentAt(LocalDateTime.now());
        req = requests.save(req);
        auditService.record(req, Action.SENT, req.getCreatedBy(), http);
        return publicBaseUrl + "/sign/" + req.getSignToken();
    }

    @Transactional
    public SignatureRequest voidRequest(String tenantId, String id, HttpServletRequest http) {
        SignatureRequest req = load(tenantId, id);
        if (Status.COMPLETED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot void a completed request");
        }
        if (Status.VOIDED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is already void");
        }
        req.setStatus(Status.VOIDED);
        req = requests.save(req);
        auditService.record(req, Action.VOIDED, req.getCreatedBy(), http);
        return req;
    }

    /** Stream the signed PDF bytes from the store (409 until COMPLETED); audit DOWNLOADED. */
    public byte[] signedPdf(String tenantId, String id, HttpServletRequest http) {
        SignatureRequest req = load(tenantId, id);
        if (!Status.COMPLETED.equals(req.getStatus()) || req.getSignedObjectKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document is not signed yet");
        }
        byte[] bytes = documentStore.get(tenantId, req.getSignedObjectKey());
        auditService.record(req, Action.DOWNLOADED, req.getCreatedBy(), http);
        return bytes;
    }

    // ── Public (token-authenticated) ───────────────────────────────────────────────

    /** Resolve the signing session, enforce IP policy, mark VIEWED, return the source bytes. */
    public SigningSession viewForSigning(String token, HttpServletRequest http) {
        SignatureRequest req = resolveSignable(token);
        enforceIpPolicy(req, Action.VIEWED, http);

        if (Status.SENT.equals(req.getStatus())) {
            req.setStatus(Status.VIEWED);
            req = requests.save(req);
        }
        auditService.record(req, Action.VIEWED, req.getSignerEmail(), http);

        byte[] source = documentStore.get(req.getTenantId(), req.getSourceObjectKey());
        boolean required = signerVerification.required(req);
        return new SigningSession(req, source, required);
    }

    /**
     * Apply the signature. Pre-checks (consent, IP policy, verification) run OUTSIDE any
     * transaction — so the BLOCKED audit recorded on an IP-policy rejection persists despite the
     * 403. The signing unit (SIGNED audit + stamp→seal→store + flip to COMPLETED) runs INSIDE one
     * transaction so a stamp/seal failure rolls back the SIGNED audit + status atomically: no
     * phantom or duplicate SIGNED event survives a failed attempt, and the request stays retryable.
     */
    public void sign(String token, byte[] signaturePng, boolean consent, String signerNameTyped,
                     HttpServletRequest http) {
        SignatureRequest req = resolveSignable(token);
        if (!consent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consent is required to sign");
        }
        enforceIpPolicy(req, Action.SIGNED, http); // records BLOCKED + 403 (persisted, no surrounding tx)
        if (signerVerification.required(req)) {
            // V1 NoneSignerVerification is never required; SIG-9 adds a verified-session gate here.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Signer verification required before signing");
        }

        String typed = boundedTyped(signerNameTyped);
        String detail = typed.isEmpty() ? "consent=true" : "consent=true; typed=" + typed;

        txTemplate.executeWithoutResult(tx -> {
            // SIGNED is an audit ACTION (status goes VIEWED→COMPLETED atomically); recorded first so
            // the Certificate of Completion includes it. Rolls back with the status on any failure.
            auditService.record(req, Action.SIGNED, req.getSignerEmail(), http, detail);

            AuditMeta meta = new AuditMeta(req.getSignerName(), req.getSignerEmail(),
                    req.getSourceSha256(), auditTrail(req.getId()));
            byte[] source = documentStore.get(req.getTenantId(), req.getSourceObjectKey());
            byte[] sealed = signatureProvider.stampAndSign(
                    new StampRequest(source, signaturePng, Field.of(req), meta));

            String signedKey = signedKey(req.getId());
            documentStore.put(req.getTenantId(), signedKey, sealed, "application/pdf");
            req.setSignedObjectKey(signedKey);
            req.setSignedSha256(SignatureSupport.sha256Hex(sealed));
            req.setSignedAt(LocalDateTime.now());
            req.setStatus(Status.COMPLETED);
            requests.save(req);
            registerSignatureDoc(req, signedKey, "signed.pdf", (long) sealed.length, req.getSignedSha256());
        });
    }

    /** Trim, length-cap (200), and strip control chars from the public signer's typed name. */
    private static String boundedTyped(String typed) {
        if (typed == null) return "";
        String t = typed.strip();
        if (t.length() > 200) t = t.substring(0, 200);
        return t.replaceAll("\\p{Cntrl}", " ").strip();
    }

    // ── Internal helpers ───────────────────────────────────────────────────────────

    private SignatureRequest load(String tenantId, String id) {
        return requests.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown signature request: " + id));
    }

    /** Resolve by token; 404 if unknown, 410 if voided/already-signed (cannot sign). */
    private SignatureRequest resolveSignable(String token) {
        SignatureRequest req = requests.findBySignToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or invalid signing link"));
        if (Status.VOIDED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This request has been voided");
        }
        if (Status.SIGNED.equals(req.getStatus()) || Status.COMPLETED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This document has already been signed");
        }
        return req;
    }

    /**
     * Opt-in IP-risk block (default empty = flag-only). Classifies, audits the block under the
     * dedicated BLOCKED action (NEVER the lifecycle action — so the Certificate of Completion can
     * never show a phantom SIGNED/VIEWED for a denied attempt), then 403. {@code attempted} is the
     * lifecycle action that was blocked, recorded only in the detail for context.
     */
    private void enforceIpPolicy(SignatureRequest req, String attempted, HttpServletRequest http) {
        if (blockedRisks.isEmpty()) return;
        String ip = ClientIp.resolve(http);
        IpRisk risk = ip != null ? ipReputation.classify(ip) : IpRisk.CLEAN;
        if (blockedRisks.contains(risk)) {
            auditService.record(req, Action.BLOCKED, req.getSignerEmail(), http,
                    "blocked " + attempted + ": " + risk);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Signing is blocked from this network (" + risk + ")");
        }
    }

    private String uniqueCode(String tenantId) {
        for (int i = 0; i < 10; i++) {
            String code = SignatureSupport.generateCode();
            if (!requests.existsByTenantIdAndCode(tenantId, code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique signature code");
    }

    private static String sourceKey(String requestId) {
        return "signatures/" + requestId + "/source.pdf";
    }

    private static String signedKey(String requestId) {
        return "signatures/" + requestId + "/signed.pdf";
    }

    private static Set<IpRisk> parseRisks(String csv) {
        Set<IpRisk> set = EnumSet.noneOf(IpRisk.class);
        if (csv == null || csv.isBlank()) return set;
        List<String> bad = new ArrayList<>();
        for (String part : csv.split(",")) {
            String token = part.trim().toUpperCase();
            if (token.isEmpty()) continue;
            try {
                set.add(IpRisk.valueOf(token));
            } catch (IllegalArgumentException e) {
                bad.add(token);
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalStateException("Invalid LUKE_SIGN_BLOCK_IP_RISK values: " + bad);
        }
        return set;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ── Value carriers ─────────────────────────────────────────────────────────────

    /** Neutral create input (controller maps its parsed payload to this). */
    public record SignatureDraft(String name, String signerEmail, String signerName,
                                 VerificationMethod verificationMethod, String signerPhone, Field field) {}

    /** Public signing session: the request, the source PDF bytes, and whether verification is required. */
    public record SigningSession(SignatureRequest request, byte[] sourcePdf, boolean verificationRequired) {}
}
