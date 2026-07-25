package com.luke.engine.capability.emailtemplate;

import com.luke.engine.capability.access.CapabilityLevel;
import com.luke.engine.capability.access.RequiresCapabilityAction;

import com.luke.engine.capability.email.EmailRequest;
import com.luke.engine.capability.email.EmailServerService;
import com.luke.engine.capability.email.EmailService;
import com.luke.engine.capability.email.PostmarkTemplateClient;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Email templates: create / read / edit, versioning (check-in → publish to Postmark),
 * retire, and send-test. Tenant-scoped via the {@code X-Tenant-Id} header; the actor
 * of record is {@code X-User-Id}. Mirrors {@link com.luke.engine.capability.form.FormDefinitionController}.
 *
 * <p>Authoring stores only the lightweight EmailDoc JSON. The UI compiles the
 * {@code html}/{@code text} in the browser and sends them on check-in/publish; the
 * backend forwards them to Postmark (which owns the rendered HTML and does the
 * {@code {{var}}} merge at send) and never persists them.
 *
 * <p>Guarded by the existing EMAIL capability (see
 * {@link com.luke.engine.capability.access.AccessWebConfig}).
 */
@RestController
@RequestMapping("/api/email-templates")
public class EmailTemplateController {

    private final EmailTemplateRepository templates;
    private final EmailTemplateVersionRepository versions;
    private final EmailTemplateAuditEventRepository audit;
    private final EmailServerService emailServers;
    private final PostmarkTemplateClient postmarkTemplates;
    private final EmailService emailService;
    private final com.luke.engine.tenant.UserDirectory userDirectory;

    public EmailTemplateController(EmailTemplateRepository templates, EmailTemplateVersionRepository versions,
                                   EmailTemplateAuditEventRepository audit, EmailServerService emailServers,
                                   PostmarkTemplateClient postmarkTemplates, EmailService emailService,
                                   com.luke.engine.tenant.UserDirectory userDirectory) {
        this.templates = templates;
        this.versions = versions;
        this.audit = audit;
        this.emailServers = emailServers;
        this.postmarkTemplates = postmarkTemplates;
        this.emailService = emailService;
        this.userDirectory = userDirectory;
    }

    /* ── request bodies ─────────────────────────────────────── */
    public record CreateTemplate(String name, String description) {}
    public record MetaPatch(String name, String description) {}
    public record DraftBody(String doc, String subject) {}
    public record CheckInBody(String doc, String subject, String html, String text, Boolean publish) {}
    public record SendTestBody(String to, Map<String, Object> model) {}

    /* ── CRUD ───────────────────────────────────────────────── */

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmailTemplate create(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @RequestBody CreateTemplate body) {
        requireTenant(tenantId);
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        EmailTemplate tpl = new EmailTemplate();
        tpl.setTenantId(tenantId);
        tpl.setCode(uniqueCode(tenantId));
        tpl.setName(body.name().trim());
        tpl.setDescription(body.description());
        tpl.setStatus("DRAFT");
        tpl.setCreatedBy(userId);
        tpl.setUpdatedBy(userId);
        EmailTemplate saved = templates.save(tpl);
        record(saved, userId, "created", null);
        return saved;
    }

    @GetMapping
    public List<EmailTemplate> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "false") boolean deleted) {
        requireTenant(tenantId);
        List<EmailTemplate> result = deleted
                ? templates.findByTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(tenantId)
                : (status == null
                    ? templates.findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId)
                    : templates.findByTenantIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId, status));
        return withNames(result);
    }

    @GetMapping("/{id}")
    public EmailTemplate get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        // Flat entity; the UI fetches versions separately via GET /{id}/versions.
        return withNames(load(tenantId, id));
    }

    @PatchMapping("/{id}")
    public EmailTemplate patchMeta(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestHeader(value = "X-User-Id", required = false) String userId,
                                   @PathVariable String id, @RequestBody MetaPatch body) {
        EmailTemplate tpl = load(tenantId, id);
        if (body.name() != null && !body.name().isBlank()) tpl.setName(body.name().trim());
        if (body.description() != null) tpl.setDescription(body.description());
        tpl.setUpdatedBy(userId);
        return templates.save(tpl);
    }

    @PutMapping("/{id}/draft")
    public EmailTemplate saveDraft(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestHeader(value = "X-User-Id", required = false) String userId,
                                   @PathVariable String id, @RequestBody DraftBody body) {
        EmailTemplate tpl = load(tenantId, id);
        tpl.setDraftDoc(body.doc());
        if (body.subject() != null) tpl.setSubject(body.subject());
        tpl.setUpdatedBy(userId);
        return templates.save(tpl);
    }

    /* ── versioning + publish ───────────────────────────────── */

    /**
     * Check in the draft as a new immutable version and publish it to Postmark. The
     * UI sends the EmailDoc plus the browser-compiled {@code html}/{@code text}; we
     * store the doc+subject on the version and push the compiled bodies to Postmark
     * under a stable per-template alias. First check-in auto-publishes; pass
     * {@code publish=true} to push a later one too.
     */
    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailTemplateVersion checkIn(@RequestHeader("X-Tenant-Id") String tenantId,
                                        @RequestHeader(value = "X-User-Id", required = false) String userId,
                                        @PathVariable String id, @RequestBody(required = false) CheckInBody body) {
        EmailTemplate tpl = load(tenantId, id);
        String doc = (body != null && body.doc() != null) ? body.doc() : tpl.getDraftDoc();
        if (doc == null || doc.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Template has no doc to check in");
        }
        String subject = (body != null && body.subject() != null) ? body.subject() : tpl.getSubject();

        int next = versions.findTopByEmailTemplateIdOrderByVersionDesc(id).map(v -> v.getVersion() + 1).orElse(1);
        EmailTemplateVersion artifact = new EmailTemplateVersion(id, next, doc, subject, userId);

        boolean firstPublish = tpl.getPublishedVersion() == null;
        boolean publish = firstPublish || Boolean.TRUE.equals(body != null ? body.publish() : null);

        if (publish) {
            String html = body != null ? body.html() : null;
            String text = body != null ? body.text() : null;
            pushToPostmark(tpl, artifact, subject, html, text);
            tpl.setPublishedVersion(next);
            if (!"RETIRED".equals(tpl.getStatus())) tpl.setStatus("PUBLISHED");
        }

        versions.save(artifact);
        tpl.setDraftDoc(doc);
        if (subject != null) tpl.setSubject(subject);
        tpl.setUpdatedBy(userId);
        templates.save(tpl);
        record(tpl, userId, "checked_in", "v" + next);
        if (publish) record(tpl, userId, "published", "v" + next);
        return artifact;
    }

    @GetMapping("/{id}/versions")
    public List<EmailTemplateVersion> listVersions(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        load(tenantId, id);
        return versions.findByEmailTemplateIdOrderByVersionAsc(id);
    }

    /** Re-push an existing version's doc to Postmark and make it the published one. */
    @PostMapping("/{id}/versions/{v}/publish")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public EmailTemplate publish(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @PathVariable String id, @PathVariable int v,
                                 @RequestBody(required = false) CheckInBody body) {
        EmailTemplate tpl = load(tenantId, id);
        EmailTemplateVersion version = version(tpl, v);
        String subject = (body != null && body.subject() != null) ? body.subject() : version.getSubject();
        String html = body != null ? body.html() : null;
        String text = body != null ? body.text() : null;
        pushToPostmark(tpl, version, subject, html, text);
        versions.save(version);
        tpl.setPublishedVersion(v);
        if (!"RETIRED".equals(tpl.getStatus())) tpl.setStatus("PUBLISHED");
        tpl.setUpdatedBy(userId);
        EmailTemplate saved = templates.save(tpl);
        record(saved, userId, "published", "v" + v);
        return saved;
    }

    /* ── retire & remove ────────────────────────────────────── */

    @PostMapping("/{id}/retire")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public EmailTemplate retire(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @PathVariable String id) {
        EmailTemplate tpl = load(tenantId, id);
        tpl.setStatus("RETIRED");
        tpl.setUpdatedBy(userId);
        EmailTemplate saved = templates.save(tpl);
        record(saved, userId, "archived", null);
        return saved;
    }

    @PostMapping("/{id}/unretire")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public EmailTemplate unretire(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable String id) {
        EmailTemplate tpl = load(tenantId, id);
        tpl.setStatus(tpl.getPublishedVersion() != null ? "PUBLISHED" : "DRAFT");
        tpl.setUpdatedBy(userId);
        EmailTemplate saved = templates.save(tpl);
        record(saved, userId, "unarchived", null);
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDelete(@RequestHeader("X-Tenant-Id") String tenantId,
                           @RequestHeader(value = "X-User-Id", required = false) String userId,
                           @PathVariable String id) {
        EmailTemplate tpl = load(tenantId, id);
        tpl.setDeletedAt(java.time.LocalDateTime.now());
        templates.save(tpl);
        record(tpl, userId, "deleted", null);
    }

    @PostMapping("/{id}/restore")
    public EmailTemplate restore(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @PathVariable String id) {
        EmailTemplate tpl = templates.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown email template: " + id));
        tpl.setDeletedAt(null);
        EmailTemplate saved = templates.save(tpl);
        record(saved, userId, "restored", null);
        return saved;
    }

    @DeleteMapping("/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresCapabilityAction(CapabilityLevel.Action.DELETE)
    public void purge(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        EmailTemplate tpl = templates.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown email template: " + id));
        versions.deleteAll(versions.findByEmailTemplateIdOrderByVersionAsc(id));
        audit.deleteByEmailTemplateId(id);
        templates.delete(tpl);
    }

    /* ── clone ──────────────────────────────────────────────── */

    /** Duplicate a template as a new DRAFT ("Copy of …"), copying the draft doc; no versions or Postmark alias carried over. */
    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailTemplate clone(@RequestHeader("X-Tenant-Id") String tenantId,
                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                               @PathVariable String id) {
        EmailTemplate src = load(tenantId, id);
        EmailTemplate copy = new EmailTemplate();
        copy.setTenantId(tenantId);
        copy.setCode(uniqueCode(tenantId));
        copy.setName("Copy of " + src.getName());
        copy.setDescription(src.getDescription());
        copy.setSubject(src.getSubject());
        copy.setStatus("DRAFT");
        copy.setDraftDoc(src.getDraftDoc());
        copy.setCreatedBy(userId);
        copy.setUpdatedBy(userId);
        EmailTemplate saved = templates.save(copy);
        record(saved, userId, "created", "cloned from " + src.getCode());
        return saved;
    }

    /* ── activity feed ──────────────────────────────────────── */

    /** Audit event with the actor's resolved display name (for the UI activity feed). */
    public record AuditView(String action, String actor, String actorName, String detail,
                            java.time.LocalDateTime at) {}

    @GetMapping("/{id}/audit")
    public List<AuditView> auditTrail(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        load(tenantId, id);
        List<EmailTemplateAuditEvent> events = audit.findByEmailTemplateIdAndTenantIdOrderByAtDesc(id, tenantId);
        Map<String, String> names = userDirectory.namesFor(events.stream().map(EmailTemplateAuditEvent::getActor).toList());
        return events.stream()
                .map(e -> new AuditView(e.getAction(), e.getActor(),
                        e.getActor() != null ? names.getOrDefault(e.getActor(), e.getActor()) : null,
                        e.getDetail(), e.getAt()))
                .toList();
    }

    /* ── send test ──────────────────────────────────────────── */

    /**
     * Send a test of the published template to {@code to}, with {@code model} merged
     * by Postmark. Reuses {@link EmailService#sendTemplate} (no new send code, no Java
     * merge) — it persists the EmailMessage audit row and submits via Postmark.
     */
    @PostMapping("/{id}/send-test")
    @ResponseStatus(HttpStatus.CREATED)
    public Object sendTest(@RequestHeader("X-Tenant-Id") String tenantId,
                           @RequestHeader(value = "X-User-Id", required = false) String userId,
                           @PathVariable String id, @RequestBody SendTestBody body) {
        EmailTemplate tpl = load(tenantId, id);
        if (tpl.getPostmarkAlias() == null || tpl.getPublishedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Publish the template before sending a test");
        }
        if (body.to() == null || body.to().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to is required");
        }
        EmailRequest req = new EmailRequest(
                null,                       // from — resolved by EmailServerService
                body.to(),
                null, null, null,           // cc, bcc, replyTo
                null,                       // subject — Postmark uses the template's stored subject
                null, null,                 // htmlBody, textBody
                null,                       // templateId
                tpl.getPostmarkAlias(),     // templateAlias
                body.model(),               // templateModel
                "email-template-test",      // tag
                null,                       // messageStream — defaults
                null,                       // metadata
                Map.of("templateCode", tpl.getCode(), "version", tpl.getPublishedVersion()));
        record(tpl, userId, "test_sent", "to " + body.to());
        return emailService.sendTemplate(tenantId, userId, req);
    }

    /* ── helpers ────────────────────────────────────────────── */

    /**
     * Push the compiled bodies to Postmark under the template's stable alias and stamp
     * the alias/templateId on both the version and the template. A missing email server
     * surfaces as a clear 409/422 (from {@link EmailServerService#resolveServerToken}).
     */
    private void pushToPostmark(EmailTemplate tpl, EmailTemplateVersion version,
                                String subject, String html, String text) {
        if ((html == null || html.isBlank()) && (text == null || text.isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Compiled html or text is required to publish to Postmark");
        }
        String token = emailServers.resolveServerToken(tpl.getTenantId());
        String alias = tpl.getPostmarkAlias() != null ? tpl.getPostmarkAlias() : aliasFor(tpl.getCode());
        PostmarkTemplateClient.UpsertResult res;
        try {
            res = postmarkTemplates.upsert(token, alias, tpl.getName(), subject, html, text);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        version.setPostmarkAlias(res.alias());
        version.setPostmarkTemplateId(res.templateId());
        tpl.setPostmarkAlias(res.alias());
    }

    /** Read-time enrichment: fill createdByName/updatedByName on one template. */
    private EmailTemplate withNames(EmailTemplate tpl) {
        withNames(List.of(tpl));
        return tpl;
    }

    /** Read-time enrichment: batch-fill createdByName/updatedByName across templates in ONE lookup. */
    private List<EmailTemplate> withNames(List<EmailTemplate> list) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (EmailTemplate t : list) {
            if (t.getCreatedBy() != null) ids.add(t.getCreatedBy());
            if (t.getUpdatedBy() != null) ids.add(t.getUpdatedBy());
        }
        Map<String, String> names = userDirectory.namesFor(ids);
        for (EmailTemplate t : list) {
            if (t.getCreatedBy() != null) t.setCreatedByName(names.getOrDefault(t.getCreatedBy(), t.getCreatedBy()));
            if (t.getUpdatedBy() != null) t.setUpdatedByName(names.getOrDefault(t.getUpdatedBy(), t.getUpdatedBy()));
        }
        return list;
    }

    /** Append an immutable audit event for a lifecycle action on {@code tpl}. */
    private void record(EmailTemplate tpl, String actor, String action, String detail) {
        audit.save(new EmailTemplateAuditEvent(tpl.getId(), tpl.getTenantId(), action, detail, actor));
    }

    private EmailTemplate load(String tenantId, String id) {
        requireTenant(tenantId);
        EmailTemplate tpl = templates.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown email template: " + id));
        if (tpl.getDeletedAt() != null) throw notFound("Email template is in trash: " + id);
        return tpl;
    }

    private EmailTemplateVersion version(EmailTemplate tpl, int v) {
        return versions.findByEmailTemplateIdAndVersion(tpl.getId(), v)
                .orElseThrow(() -> notFound("Unknown version v" + v + " for " + tpl.getCode()));
    }

    private String uniqueCode(String tenantId) {
        String code = EmailTemplateSupport.generateCode();
        while (templates.existsByTenantIdAndCode(tenantId, code)) code = EmailTemplateSupport.generateCode();
        return code;
    }

    /** Postmark alias from the template code: lowercased, non-alnum → '-'. Stable across versions. */
    private static String aliasFor(String code) {
        return code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
