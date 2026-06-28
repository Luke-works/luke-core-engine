package com.luke.engine.capability.signature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.capability.signature.SignatureService.SignatureDraft;
import com.luke.engine.tenant.UserDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticated, tenant-scoped signature API ({@code /api/signatures}). Identity comes from the
 * gateway-asserted {@code X-Tenant-Id} / {@code X-User-Id} headers (the dev filter in standalone).
 * Responses use contract-shaped DTO views — the entity's storage keys / token / hashes / tenantId
 * are NEVER serialized (per the shared API contract).
 */
@RestController
@RequestMapping("/api/signatures")
public class SignatureController {

    private final SignatureService service;
    private final UserDirectory userDirectory;
    private final ObjectMapper mapper;

    public SignatureController(SignatureService service, UserDirectory userDirectory, ObjectMapper mapper) {
        this.service = service;
        this.userDirectory = userDirectory;
        this.mapper = mapper;
    }

    /** POST /api/signatures — multipart: file=<pdf>, json={name,signerEmail,signerName,field,...}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SignatureView create(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @RequestParam("file") MultipartFile file,
                                @RequestParam("json") String json,
                                HttpServletRequest http) {
        requireTenant(tenantId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file (PDF) is required");
        }
        CreatePayload payload = parse(json);
        SignatureDraft draft = payload.toDraft();
        byte[] pdf = readBytes(file);
        if (!isPdf(pdf)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "file must be a PDF (the %PDF- header is required)");
        }
        SignatureRequest req = service.create(tenantId, userId, draft, pdf, http);
        return view(req);
    }

    /** GET /api/signatures — the tenant's requests, newest first (no bytes). */
    @GetMapping
    public List<SignatureView> list(@RequestHeader("X-Tenant-Id") String tenantId) {
        requireTenant(tenantId);
        List<SignatureRequest> all = service.list(tenantId);
        Map<String, String> names = userDirectory.namesFor(
                all.stream().map(SignatureRequest::getCreatedBy).filter(java.util.Objects::nonNull).toList());
        return all.stream().map(r -> SignatureView.from(r, names.get(r.getCreatedBy()))).toList();
    }

    /** GET /api/signatures/{id} — the request + its IP-stamped audit trail. */
    @GetMapping("/{id}")
    public Detail get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        SignatureRequest req = service.get(tenantId, id);
        List<AuditView> trail = service.auditTrail(tenantId, id).stream().map(AuditView::from).toList();
        return new Detail(view(req), trail);
    }

    /** POST /api/signatures/{id}/send — mint the signing link. */
    @PostMapping("/{id}/send")
    public Map<String, Object> send(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @PathVariable String id, HttpServletRequest http) {
        requireTenant(tenantId);
        return Map.of("signUrl", service.send(tenantId, id, http));
    }

    /** GET /api/signatures/{id}/signed.pdf — stream the sealed PDF (409 until COMPLETED). */
    @GetMapping("/{id}/signed.pdf")
    public ResponseEntity<byte[]> signedPdf(@RequestHeader("X-Tenant-Id") String tenantId,
                                            @PathVariable String id, HttpServletRequest http) {
        requireTenant(tenantId);
        byte[] bytes = service.signedPdf(tenantId, id, http);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"signed.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }

    /** POST /api/signatures/{id}/void — cancel the request. */
    @PostMapping("/{id}/void")
    public SignatureView voidRequest(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @PathVariable String id, HttpServletRequest http) {
        requireTenant(tenantId);
        return view(service.voidRequest(tenantId, id, http));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private SignatureView view(SignatureRequest req) {
        return SignatureView.from(req, userDirectory.nameFor(req.getCreatedBy()));
    }

    private CreatePayload parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "json metadata is required");
        }
        CreatePayload payload;
        try {
            payload = mapper.readValue(json, CreatePayload.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid json metadata: " + e.getMessage());
        }
        payload.validate();
        return payload;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
    }

    /** Authoritative PDF check: the actual bytes must start with the "%PDF-" header
     *  (content-type/filename are client-controlled and not trusted on their own). */
    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-';
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    // ── DTOs ────────────────────────────────────────────────────────────────────────

    /** Multipart json payload: {name, signerEmail, signerName, field, verificationMethod?, signerPhone?}. */
    public record CreatePayload(String name, String signerEmail, String signerName,
                                FieldPayload field, String verificationMethod, String signerPhone) {

        void validate() {
            if (isBlank(name)) throw bad("name is required");
            if (isBlank(signerEmail)) throw bad("signerEmail is required");
            if (isBlank(signerName)) throw bad("signerName is required");
            if (field == null) throw bad("field {page,x,y,w,h} is required");
        }

        SignatureDraft toDraft() {
            return new SignatureDraft(name.trim(), signerEmail.trim(), signerName.trim(),
                    parseMethod(verificationMethod), signerPhone, field.toField());
        }

        private static VerificationMethod parseMethod(String m) {
            if (m == null || m.isBlank()) return VerificationMethod.NONE;
            try {
                return VerificationMethod.valueOf(m.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw bad("Unknown verificationMethod: " + m);
            }
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }

        private static ResponseStatusException bad(String msg) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
    }

    /** Field placement in UI coordinates (origin top-left). */
    public record FieldPayload(int page, double x, double y, double w, double h) {
        Field toField() {
            return new Field(page, x, y, w, h);
        }
    }

    /** Contract-shaped request view — NO storage keys / token / hashes / tenantId. */
    public record SignatureView(String id, String code, String name, String status,
                                String signerEmail, String signerName, String verificationMethod,
                                Field field, String createdBy, String createdByName,
                                Long retainUntil, Long createdAt, Long sentAt, Long signedAt) {

        static SignatureView from(SignatureRequest r, String createdByName) {
            return new SignatureView(
                    r.getId(), r.getCode(), r.getName(), r.getStatus(),
                    r.getSignerEmail(), r.getSignerName(),
                    r.getVerificationMethod() == null ? null : r.getVerificationMethod().name(),
                    Field.of(r), r.getCreatedBy(), createdByName,
                    SignatureSupport.epochMillis(r.getRetainUntil()),
                    SignatureSupport.epochMillis(r.getCreatedAt()),
                    SignatureSupport.epochMillis(r.getSentAt()),
                    SignatureSupport.epochMillis(r.getSignedAt()));
        }
    }

    /** Audit row view for the UI history (action · time · IP · risk · location · device). */
    public record AuditView(String action, String actor, String ipAddress, String userAgent,
                            String geoCountry, String geoCity, String ipRisk, Long at) {

        static AuditView from(SignatureAuditEvent e) {
            return new AuditView(e.getAction(), e.getActor(), e.getIpAddress(), e.getUserAgent(),
                    e.getGeoCountry(), e.getGeoCity(),
                    e.getIpRisk() == null ? null : e.getIpRisk().name(),
                    SignatureSupport.epochMillis(e.getAt()));
        }
    }

    /** GET /{id} response: the request + its audit trail. */
    public record Detail(SignatureView request, List<AuditView> audit) {}
}
