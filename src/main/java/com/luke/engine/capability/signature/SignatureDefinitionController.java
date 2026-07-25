package com.luke.engine.capability.signature;

import com.luke.engine.capability.access.CapabilityLevel;
import com.luke.engine.capability.access.RequiresCapabilityAction;

import com.luke.engine.tenant.UserDirectory;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticated, tenant-scoped design-time API for SIGNATURE DEFINITIONS
 * ({@code /api/signature-definitions}). Mirrors the forms {@code FormDefinitionController} and the
 * shared {@code @lukeflow/sign-core} client routes. Identity from gateway-asserted
 * {@code X-Tenant-Id}/{@code X-User-Id}. DTO views never leak {@code tenantId}/lock internals
 * beyond what the builder needs; {@code status} is lower-cased to the contract vocabulary.
 */
@RestController
@RequestMapping("/api/signature-definitions")
public class SignatureDefinitionController {

    private final SignatureDefinitionService service;
    private final UserDirectory userDirectory;

    public SignatureDefinitionController(SignatureDefinitionService service, UserDirectory userDirectory) {
        this.service = service;
        this.userDirectory = userDirectory;
    }

    @GetMapping
    public List<DefinitionView> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @RequestParam(value = "deleted", defaultValue = "false") boolean deleted) {
        requireTenant(tenantId);
        return service.list(tenantId, deleted).stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionView create(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @RequestBody CreateBody body) {
        requireTenant(tenantId);
        return view(service.create(tenantId, userId, body == null ? null : body.name(),
                body == null ? null : body.description()));
    }

    @GetMapping("/{id}")
    public DefinitionView get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        return view(service.get(tenantId, id));
    }

    @PatchMapping("/{id}")
    public DefinitionView patch(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @PathVariable String id, @RequestBody MetaPatch body) {
        requireTenant(tenantId);
        return view(service.patchMeta(tenantId, id, userId, body == null ? null : body.name(),
                body == null ? null : body.description()));
    }

    @DeleteMapping("/{id}")
    public void softDelete(@RequestHeader("X-Tenant-Id") String tenantId,
                           @RequestHeader(value = "X-User-Id", required = false) String userId,
                           @PathVariable String id) {
        requireTenant(tenantId);
        service.softDelete(tenantId, id, userId);
    }

    // ── draft + versions ──────────────────────────────────────────────────────────────
    @PutMapping("/{id}/draft")
    public void saveDraft(@RequestHeader("X-Tenant-Id") String tenantId,
                          @RequestHeader(value = "X-User-Id", required = false) String userId,
                          @PathVariable String id, @RequestBody DraftBody body) {
        requireTenant(tenantId);
        service.saveDraft(tenantId, id, userId, body == null ? null : body.schema());
    }

    @PostMapping("/{id}/versions")
    public ArtifactView checkIn(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @PathVariable String id, @RequestBody DraftBody body) {
        requireTenant(tenantId);
        return ArtifactView.from(service.checkIn(tenantId, id, userId, body == null ? null : body.schema()));
    }

    @GetMapping("/{id}/versions")
    public List<ArtifactView> versions(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        return service.versions(tenantId, id).stream().map(ArtifactView::from).toList();
    }

    @GetMapping("/{id}/versions/{v}")
    public ArtifactView version(@RequestHeader("X-Tenant-Id") String tenantId,
                                @PathVariable String id, @PathVariable int v) {
        requireTenant(tenantId);
        return ArtifactView.from(service.version(tenantId, id, v));
    }

    @PostMapping("/{id}/versions/{v}/publish")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public void publish(@RequestHeader("X-Tenant-Id") String tenantId,
                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                        @PathVariable String id, @PathVariable int v) {
        requireTenant(tenantId);
        service.publish(tenantId, id, v, userId);
    }

    @PostMapping("/{id}/versions/{v}/restore")
    public void restoreVersion(@RequestHeader("X-Tenant-Id") String tenantId,
                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                               @PathVariable String id, @PathVariable int v) {
        requireTenant(tenantId);
        service.restoreVersion(tenantId, id, v, userId);
    }

    // ── legal review + lifecycle ────────────────────────────────────────────────────────
    @PostMapping("/{id}/sign-off")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public DefinitionView signOff(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable String id) {
        requireTenant(tenantId);
        return view(service.signOff(tenantId, id, userId));
    }

    @PostMapping("/{id}/checkout")
    public DefinitionView checkout(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestHeader(value = "X-User-Id", required = false) String userId,
                                   @PathVariable String id,
                                   @RequestParam(value = "force", defaultValue = "false") boolean force) {
        requireTenant(tenantId);
        return view(service.checkout(tenantId, id, userId, force));
    }

    @PostMapping("/{id}/release")
    public void release(@RequestHeader("X-Tenant-Id") String tenantId,
                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                        @PathVariable String id,
                        @RequestParam(value = "force", defaultValue = "false") boolean force) {
        requireTenant(tenantId);
        service.release(tenantId, id, userId, force);
    }

    @PostMapping("/{id}/discard")
    public void discard(@RequestHeader("X-Tenant-Id") String tenantId,
                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                        @PathVariable String id) {
        requireTenant(tenantId);
        service.discard(tenantId, id, userId);
    }

    @PostMapping("/{id}/retire")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public void retire(@RequestHeader("X-Tenant-Id") String tenantId,
                       @RequestHeader(value = "X-User-Id", required = false) String userId,
                       @PathVariable String id) {
        requireTenant(tenantId);
        service.retire(tenantId, id, userId);
    }

    @PostMapping("/{id}/unretire")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public void unretire(@RequestHeader("X-Tenant-Id") String tenantId,
                         @RequestHeader(value = "X-User-Id", required = false) String userId,
                         @PathVariable String id) {
        requireTenant(tenantId);
        service.unretire(tenantId, id, userId);
    }

    @PostMapping("/{id}/restore")
    public void restore(@RequestHeader("X-Tenant-Id") String tenantId,
                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                        @PathVariable String id) {
        requireTenant(tenantId);
        service.restore(tenantId, id, userId);
    }

    @DeleteMapping("/{id}/purge")
    @RequiresCapabilityAction(CapabilityLevel.Action.DELETE)
    public void purge(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        service.purge(tenantId, id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditView> audit(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        return service.auditTrail(tenantId, id).stream().map(this::auditView).toList();
    }

    // ── source document ─────────────────────────────────────────────────────────────────
    @PostMapping(value = "/{id}/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SignatureDefinitionService.UploadedDocument uploadDocument(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String id,
            @RequestParam("file") MultipartFile file) {
        requireTenant(tenantId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file (PDF) is required");
        }
        return service.uploadDocument(tenantId, id, userId, readBytes(file), file.getOriginalFilename());
    }

    @GetMapping("/{id}/document/{key}")
    public ResponseEntity<byte[]> document(@RequestHeader("X-Tenant-Id") String tenantId,
                                           @PathVariable String id, @PathVariable String key) {
        requireTenant(tenantId);
        byte[] bytes = service.fetchDocument(tenantId, id, key);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────
    private DefinitionView view(SignatureDefinition def) {
        Optional<SignatureVersion> latest = service.latestVersion(def.getId());
        return DefinitionView.from(def, latest,
                userDirectory.nameFor(def.getCreatedBy()), userDirectory.nameFor(def.getUpdatedBy()));
    }

    private AuditView auditView(SignatureDefinitionAuditEvent e) {
        return new AuditView(e.getAction(), e.getDetail(), e.getActor(),
                userDirectory.nameFor(e.getActor()), SignatureSupport.epochMillis(e.getAt()));
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    private static String statusView(String status) {
        return switch (status) {
            case SignatureDefinitionService.PUBLISHED -> "published";
            case SignatureDefinitionService.RETIRED -> "archived";
            default -> "draft";
        };
    }

    // ── DTOs (contract-shaped; mirror @lukeflow/sign-core view models) ─────────────────────
    public record CreateBody(String name, String description) {}
    public record MetaPatch(String name, String description) {}
    public record DraftBody(String schema) {}

    /** StoredSignatureDefinition view. */
    public record DefinitionView(String id, String code, String name, String description, String schema,
                                 String status, Integer publishedVersion, int latestVersion,
                                 boolean latestVersionSignedOff, String lockedBy, Long deletedAt,
                                 String createdBy, String updatedBy, String createdByName, String updatedByName,
                                 Long createdAt, Long updatedAt, Long lastReviewedAt, String lastReviewedBy) {

        static DefinitionView from(SignatureDefinition d, Optional<SignatureVersion> latest,
                                   String createdByName, String updatedByName) {
            return new DefinitionView(
                    d.getId(), d.getCode(), d.getName(), d.getDescription(), d.getDraftSchema(),
                    statusView(d.getStatus()), d.getPublishedVersion(),
                    latest.map(SignatureVersion::getVersion).orElse(0),
                    latest.map(v -> v.getSignedOffAt() != null).orElse(false),
                    d.getLockedBy(), SignatureSupport.epochMillis(d.getDeletedAt()),
                    d.getCreatedBy(), d.getUpdatedBy(), createdByName, updatedByName,
                    SignatureSupport.epochMillis(d.getCreatedAt()), SignatureSupport.epochMillis(d.getUpdatedAt()),
                    SignatureSupport.epochMillis(d.getLastReviewedAt()), d.getLastReviewedBy());
        }
    }

    /** SignatureArtifact view (a version). */
    public record ArtifactView(int version, String schema, Long checkedInAt, String by,
                               Long signedOffAt, String signedOffBy) {
        static ArtifactView from(SignatureVersion v) {
            return new ArtifactView(v.getVersion(), v.getSchema(), SignatureSupport.epochMillis(v.getCheckedInAt()),
                    v.getCheckedInBy(), SignatureSupport.epochMillis(v.getSignedOffAt()), v.getSignedOffBy());
        }
    }

    /** SignatureDefinitionAudit view. */
    public record AuditView(String action, String detail, String actor, String actorName, Long at) {}
}
