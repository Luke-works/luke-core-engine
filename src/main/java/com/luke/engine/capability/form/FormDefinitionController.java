package com.luke.engine.capability.form;

import com.luke.engine.capability.access.CapabilityLevel;
import com.luke.engine.capability.access.RequiresCapabilityAction;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
 * Form definitions: create / read / edit, versioning (check-in → publish), and
 * retire. Tenant-scoped via the {@code X-Tenant-Id} header (set by the
 * core-engine proxy), matching {@link com.luke.engine.capability.capability.SubscriptionController}.
 *
 * <p>Addressed by internal {@code id} for authoring routes and by stable
 * {@code code} for resolve routes (what a renderer / process formKey uses).
 */
@RestController
@RequestMapping("/api/form-definitions")
public class FormDefinitionController {

    private final FormDefinitionRepository forms;
    private final FormVersionRepository versions;
    private final FormAuditEventRepository audit;
    private final EmbedTokens embedTokens;
    private final com.luke.engine.tenant.UserDirectory userDirectory;
    private final com.luke.engine.branding.BrandingPolicy branding;
    private final FormEmbedSiteRepository embedSites;

    /** A concurrent edit (draft save / lock checkout) lost the optimistic-lock race
     *  (#57) — tell the client to reload rather than silently clobbering. */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.dao.OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> onConcurrentEdit() {
        return Map.of("error", "Conflict",
                "message", "This form was changed by someone else — reload and try again.");
    }

    public FormDefinitionController(FormDefinitionRepository forms, FormVersionRepository versions,
                                    FormAuditEventRepository audit, EmbedTokens embedTokens,
                                    com.luke.engine.tenant.UserDirectory userDirectory,
                                    com.luke.engine.branding.BrandingPolicy branding,
                                    FormEmbedSiteRepository embedSites) {
        this.forms = forms;
        this.versions = versions;
        this.audit = audit;
        this.embedTokens = embedTokens;
        this.userDirectory = userDirectory;
        this.branding = branding;
        this.embedSites = embedSites;
    }

    /* ── request bodies ─────────────────────────────────────── */
    public record CreateForm(String name, String description, String kind) {}
    /** {@code showBranding} is nullable: absent = leave the current setting alone. */
    public record MetaPatch(String name, String description, String allowedEmbedOrigins, Boolean showBranding) {}
    public record DraftBody(String schema) {}
    public record CheckInBody(String schema, Boolean publish) {}
    public record SubmissionHandlingBody(String mode) {}
    public record OutboundConfigBody(Map<String, String> roles) {}
    /** {@code version} is optional on PINNED — absent pins the current published version. */
    public record EmbedVersionBody(String mode, Integer version) {}

    /* ── CRUD ───────────────────────────────────────────────── */

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FormDefinition create(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @RequestBody CreateForm body) {
        requireTenant(tenantId);
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        FormDefinition form = new FormDefinition();
        form.setTenantId(tenantId);
        form.setCode(uniqueCode(tenantId));
        form.setName(body.name().trim());
        form.setDescription(body.description());
        form.setKind(normalizeKind(body.kind()));
        form.setStatus("DRAFT");
        form.setCreatedBy(userId);
        form.setUpdatedBy(userId);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "created", null);
        return withBrandingLock(saved);
    }

    @GetMapping
    public List<FormDefinition> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "false") boolean deleted) {
        requireTenant(tenantId);
        List<FormDefinition> result = deleted
                ? forms.findByTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(tenantId)
                : (status == null
                    ? forms.findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId)
                    : forms.findByTenantIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId, status));
        return withNames(result);
    }

    @GetMapping("/{id}")
    public FormDefinition get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return withNames(load(tenantId, id));
    }

    @GetMapping("/by-code/{code}")
    public FormDefinition getByCode(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String code) {
        return withNames(loadByCode(tenantId, code));
    }

    /**
     * Mint an opaque, signed embed token for this form (requires a published
     * version). The token wraps tenant+code and is what the public embed surface
     * (iframe) and inbound webhook resolve to.
     */
    @GetMapping("/{id}/embed-token")
    public Map<String, Object> embedToken(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        requireEmbeddable(form);
        return embedTokenResponse(tenantId, form);
    }

    /**
     * Revoke every embed token previously issued for this form by bumping its embed-key version, and
     * return a fresh token at the new version (Route B M4). Old tokens then fail the version check on
     * the public embed surface (404). Use when a token leaks or a partner's access ends.
     */
    @PostMapping("/{id}/embed-token/rotate")
    public Map<String, Object> rotateEmbedToken(@RequestHeader("X-Tenant-Id") String tenantId,
                                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                                @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        requireEmbeddable(form);
        form.setEmbedKeyVersion(form.getEmbedKeyVersion() + 1);
        form.setUpdatedBy(userId);
        forms.save(form);
        return embedTokenResponse(tenantId, form);
    }

    /**
     * Inbound only: record what happens to submissions (e.g. "COLLECT"). Deciding this is what
     * unlocks the embed surface — an inbound form with no submission handling stays un-embeddable.
     */
    @PutMapping("/{id}/submission-handling")
    public FormDefinition setSubmissionHandling(@RequestHeader("X-Tenant-Id") String tenantId,
                                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                                @PathVariable String id, @RequestBody SubmissionHandlingBody body) {
        FormDefinition form = load(tenantId, id);
        if (!FormDefinition.KIND_INBOUND.equals(form.getKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Submission handling applies to inbound forms only");
        }
        String mode = body.mode() == null || body.mode().isBlank() ? "COLLECT" : body.mode().trim();
        form.setSubmissionHandling(mode);
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    /**
     * Outbound only: store the per-field fill-role map (fieldKey → PREPARER | RECIPIENT | EITHER).
     * Recipient identity (firstName/lastName/email) is always preparer-provided and implicit.
     */
    @PutMapping("/{id}/outbound-config")
    public FormDefinition setOutboundConfig(@RequestHeader("X-Tenant-Id") String tenantId,
                                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                                            @PathVariable String id, @RequestBody OutboundConfigBody body) {
        FormDefinition form = load(tenantId, id);
        if (!FormDefinition.KIND_OUTBOUND.equals(form.getKind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Outbound config applies to outbound forms only");
        }
        Map<String, String> roles = body.roles() == null ? Map.of() : body.roles();
        for (Map.Entry<String, String> e : roles.entrySet()) {
            if (!OUTBOUND_ROLES.contains(e.getValue())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid role '" + e.getValue() + "' for field '" + e.getKey() + "' (PREPARER | RECIPIENT | EITHER)");
            }
        }
        form.setOutboundRolesJson(writeJson(roles));
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    private void requirePublished(FormDefinition form) {
        if (form.getPublishedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Publish the form before embedding it");
        }
    }

    /** Embedding requires a published INBOUND form whose submission handling has been decided. */
    private void requireEmbeddable(FormDefinition form) {
        requirePublished(form);
        if (FormDefinition.KIND_OUTBOUND.equals(form.getKind())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Outbound forms are sent to a recipient, not embedded.");
        }
        if (form.getSubmissionHandling() == null || form.getSubmissionHandling().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Decide what happens to submissions before embedding this form.");
        }
    }

    private static final java.util.Set<String> OUTBOUND_ROLES = java.util.Set.of("PREPARER", "RECIPIENT", "EITHER");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String normalizeKind(String kind) {
        if (kind == null) return FormDefinition.KIND_INBOUND;
        String k = kind.trim().toUpperCase(java.util.Locale.ROOT);
        return FormDefinition.KIND_OUTBOUND.equals(k) ? FormDefinition.KIND_OUTBOUND : FormDefinition.KIND_INBOUND;
    }

    private static String writeJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Which version this form's embeds are serving, and whether a newer published version is waiting.
     * The whole point of pinning is that publishing does NOT silently change a live embed, so the author
     * needs to be told that a publish hasn't reached fillers yet.
     */
    @GetMapping("/{id}/embed-version")
    public Map<String, Object> embedVersion(@RequestHeader("X-Tenant-Id") String tenantId,
                                            @PathVariable String id) {
        return embedVersionResponse(load(tenantId, id));
    }

    /**
     * Set the embed version policy: {@code {"mode":"AUTO"}} to always serve the published version, or
     * {@code {"mode":"PINNED","version":5}} to hold fillers on v5 until updated. Omitting the version on
     * PINNED pins the CURRENT published version — which is what "Update embeds to v6" sends.
     */
    @PutMapping("/{id}/embed-version")
    public Map<String, Object> setEmbedVersion(@RequestHeader("X-Tenant-Id") String tenantId,
                                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                                               @PathVariable String id, @RequestBody EmbedVersionBody body) {
        FormDefinition form = load(tenantId, id);
        requireEmbeddable(form);
        String mode = body == null || body.mode() == null ? "" : body.mode().trim().toUpperCase(java.util.Locale.ROOT);

        if (FormDefinition.EMBED_MODE_AUTO.equals(mode)) {
            form.setEmbedVersionMode(FormDefinition.EMBED_MODE_AUTO);
            form.setEmbedVersion(null);
        } else if (FormDefinition.EMBED_MODE_PINNED.equals(mode)) {
            // Default to the published version: "pin what is live right now".
            int target = body.version() != null ? body.version() : form.getPublishedVersion();
            // A pin must point at a real, checked-in version, or the embed would silently fall back to
            // published and the UI would claim a pin that isn't honoured.
            if (versions.findByFormIdAndVersion(form.getId(), target).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown version v" + target + " for " + form.getCode());
            }
            form.setEmbedVersionMode(FormDefinition.EMBED_MODE_PINNED);
            form.setEmbedVersion(target);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode must be " + FormDefinition.EMBED_MODE_AUTO + " or " + FormDefinition.EMBED_MODE_PINNED);
        }
        form.setUpdatedBy(userId);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "embed_version_set",
                saved.getEmbedVersionMode() + (saved.getEmbedVersion() != null ? " v" + saved.getEmbedVersion() : ""));
        return embedVersionResponse(saved);
    }

    /** The websites we have OBSERVED framing this form (from the embed page's Referer). Intelligence for
     *  the author, never a permission — see {@link FormEmbedSite}. */
    @GetMapping("/{id}/embed-sites")
    public List<Map<String, Object>> embedSites(@RequestHeader("X-Tenant-Id") String tenantId,
                                                @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        return embedSites.findByTenantIdAndFormCodeOrderByLastSeenAtDesc(tenantId, form.getCode()).stream()
                .map(s -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
                    m.put("origin", s.getOrigin());
                    m.put("firstSeenAt", s.getFirstSeenAt());
                    m.put("lastSeenAt", s.getLastSeenAt());
                    m.put("renderCount", s.getRenderCount()); // sampled
                    // Whether this observed origin is covered by the form's frame-ancestors allowlist.
                    // An empty allowlist means "any site", so everything is allowed.
                    m.put("allowed", FrameAncestors.allows(form.getAllowedEmbedOrigins(), s.getOrigin()));
                    return m;
                })
                .toList();
    }

    private Map<String, Object> embedVersionResponse(FormDefinition form) {
        Integer serving = EmbedVersions.resolve(form,
                pinned -> versions.findByFormIdAndVersion(form.getId(), pinned).isPresent());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("mode", form.getEmbedVersionMode());
        out.put("pinnedVersion", form.getEmbedVersion());
        out.put("publishedVersion", form.getPublishedVersion());
        out.put("servingVersion", serving);
        out.put("updateAvailable", EmbedVersions.updateAvailable(form, serving));
        return out;
    }

    private Map<String, Object> embedTokenResponse(String tenantId, FormDefinition form) {
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("token", embedTokens.sign(tenantId, form.getCode(), form.getEmbedKeyVersion()));
        out.put("code", form.getCode());
        out.put("allowedEmbedOrigins", form.getAllowedEmbedOrigins()); // null = any site (public default)
        return out;
    }

    @PatchMapping("/{id}")
    public FormDefinition patchMeta(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId,
                                    @PathVariable String id, @RequestBody MetaPatch body) {
        FormDefinition form = load(tenantId, id);
        if (body.name() != null && !body.name().isBlank()) form.setName(body.name().trim());
        if (body.description() != null) form.setDescription(body.description());
        // Sanitize the embed allowlist to well-formed origins before storing (empty string clears it
        // back to the public default). Route B M2 — drives the embed surface's frame-ancestors.
        // FAIL CLOSED: a non-blank value with no valid origin is a config mistake — reject it rather
        // than store null and silently make the form framable by any site (review finding).
        if (body.allowedEmbedOrigins() != null) {
            if (FrameAncestors.isAllInvalid(body.allowedEmbedOrigins())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No valid embed origins found. Use full origins like https://example.com (one per line).");
            }
            form.setAllowedEmbedOrigins(FrameAncestors.normalizeList(body.allowedEmbedOrigins()));
        }
        // "Developed at Lukeflow" badge. Turning it OFF is a paid-plan option, so enforce the plan HERE
        // as well as in the UI: the browser is not the boundary, and a free tenant scripting this PATCH
        // must not be able to strip our attribution. Turning it back ON is always allowed.
        boolean brandingChanged = body.showBranding() != null && body.showBranding() != form.isShowBranding();
        if (brandingChanged) {
            if (!body.showBranding() && !branding.canHideBadge(tenantId)) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Hiding the “Developed at Lukeflow” badge is available on paid plans.");
            }
            form.setShowBranding(body.showBranding());
        }
        form.setUpdatedBy(userId);
        FormDefinition saved = forms.save(form);
        // Audit AFTER the save so a lost optimistic-lock race (409) can't leave a "branding_hidden"
        // event describing a change that never landed.
        if (brandingChanged) {
            record(saved, userId, saved.isShowBranding() ? "branding_shown" : "branding_hidden", null);
        }
        return withBrandingLock(saved);
    }

    @PutMapping("/{id}/draft")
    public FormDefinition saveDraft(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId,
                                    @PathVariable String id, @RequestBody DraftBody body) {
        FormDefinition form = load(tenantId, id);
        form.setDraftSchema(body.schema());
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    /* ── resolve (by code) ──────────────────────────────────── */

    /** Resolve a schema for a renderer / process. pin = published (default) | latest | draft | v{n}. */
    @GetMapping("/by-code/{code}/schema")
    public Map<String, Object> resolveSchema(@RequestHeader("X-Tenant-Id") String tenantId,
                                             @PathVariable String code,
                                             @RequestParam(defaultValue = "published") String pin) {
        FormDefinition form = loadByCode(tenantId, code);
        int resolved;
        String schema;
        switch (pin) {
            case "draft" -> { return Map.of("code", code, "version", 0, "schema", nullToEmpty(form.getDraftSchema())); }
            case "latest" -> {
                FormVersion v = versions.findTopByFormIdOrderByVersionDesc(form.getId())
                        .orElseThrow(() -> notFound("No versions for " + code));
                resolved = v.getVersion(); schema = v.getSchema();
            }
            case "published" -> {
                if (form.getPublishedVersion() == null) throw notFound("No published version for " + code);
                resolved = form.getPublishedVersion();
                schema = version(form, resolved).getSchema();
            }
            default -> {
                int n = parsePin(pin);
                resolved = n; schema = version(form, n).getSchema();
            }
        }
        return Map.of("code", code, "version", resolved, "schema", schema);
    }

    /** Field→variable contract derived from the resolved schema. */
    @GetMapping("/by-code/{code}/fields")
    public Map<String, Object> fields(@RequestHeader("X-Tenant-Id") String tenantId,
                                      @PathVariable String code,
                                      @RequestParam(defaultValue = "published") String pin) {
        Map<String, Object> resolved = resolveSchema(tenantId, code, pin);
        return Map.of(
                "code", code,
                "version", resolved.get("version"),
                "fields", FormSupport.extractFields((String) resolved.get("schema")));
    }

    /* ── versioning ─────────────────────────────────────────── */

    /**
     * Check in (compile) the draft as a new immutable version. This is a SNAPSHOT only — it
     * never publishes (publishing is a separate, sign-off-gated step) and deliberately accepts
     * a schema with validation errors / work-in-progress. The {@code publish} body field is
     * ignored on purpose; promote a version via {@code POST /versions/{v}/publish}.
     */
    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public FormVersion checkIn(@RequestHeader("X-Tenant-Id") String tenantId,
                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                               @PathVariable String id, @RequestBody(required = false) CheckInBody body) {
        FormDefinition form = load(tenantId, id);
        String schema = (body != null && body.schema() != null) ? body.schema() : form.getDraftSchema();
        if (schema == null || schema.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Form has no schema to check in");
        }
        int next = versions.findTopByFormIdOrderByVersionDesc(id).map(v -> v.getVersion() + 1).orElse(1);
        FormVersion artifact = versions.save(new FormVersion(id, next, schema, userId));

        // Snapshot the draft to match the new version and release the edit lock. Status is left
        // untouched: a DRAFT stays DRAFT (publish is the deliberate go-live), and an already
        // PUBLISHED form keeps its currently-live version until a newer one is explicitly published.
        form.setDraftSchema(schema);
        form.setLockedBy(null); // checking in releases the edit lock
        form.setLockedAt(null);
        form.setUpdatedBy(userId);
        forms.save(form);
        record(form, userId, "checked_in", "v" + next);
        return artifact;
    }

    @GetMapping("/{id}/versions")
    public List<FormVersion> listVersions(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        load(tenantId, id);
        return versions.findByFormIdOrderByVersionAsc(id);
    }

    @GetMapping("/{id}/versions/{v}")
    public FormVersion getVersion(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @PathVariable String id, @PathVariable int v) {
        FormDefinition form = load(tenantId, id);
        return version(form, v);
    }

    /** Promote a checked-in version to live. Gated: the version must be SIGNED OFF (tested) first. */
    @PostMapping("/{id}/versions/{v}/publish")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public FormDefinition publish(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable String id, @PathVariable int v) {
        FormDefinition form = load(tenantId, id);
        FormVersion target = version(form, v); // 404 if missing
        if (target.getSignedOffAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "v" + v + " hasn't been signed off — test and sign it off before publishing");
        }
        form.setPublishedVersion(v);
        if (!"RETIRED".equals(form.getStatus())) form.setStatus("PUBLISHED");
        form.setUpdatedBy(userId);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "published", "v" + v);
        return saved;
    }

    /** Load an older version back into the editable draft. */
    @PostMapping("/{id}/versions/{v}/restore")
    public FormDefinition restoreVersion(@RequestHeader("X-Tenant-Id") String tenantId,
                                         @RequestHeader(value = "X-User-Id", required = false) String userId,
                                         @PathVariable String id, @PathVariable int v) {
        FormDefinition form = load(tenantId, id);
        form.setDraftSchema(version(form, v).getSchema());
        form.setUpdatedBy(userId);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "restored_to_draft", "v" + v);
        return saved;
    }

    /* ── retire & remove ────────────────────────────────────── */

    @PostMapping("/{id}/retire")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public FormDefinition retire(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        form.setStatus("RETIRED");
        form.setUpdatedBy(userId);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "archived", null);
        return saved;
    }

    @PostMapping("/{id}/unretire")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public FormDefinition unretire(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestHeader(value = "X-User-Id", required = false) String userId,
                                   @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        form.setStatus(form.getPublishedVersion() != null ? "PUBLISHED" : "DRAFT");
        form.setUpdatedBy(userId);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "unarchived", null);
        return saved;
    }

    /**
     * Sign off the LATEST checked-in version — records it passed its self-test. Publishing that
     * version is gated on this. The "tested" stamp lives on the immutable version (so it can't go
     * stale when the draft is edited) and is mirrored onto the definition for the builder's badge.
     * 422 if the form has no versions yet — check in before signing off.
     */
    @PostMapping("/{id}/sign-off")
    @RequiresCapabilityAction(CapabilityLevel.Action.PUBLISH)
    public FormDefinition signOff(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        FormVersion latest = versions.findTopByFormIdOrderByVersionDesc(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Check in a version before signing off"));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        latest.setSignedOffAt(now);
        latest.setSignedOffBy(userId);
        versions.save(latest);
        // Mirror onto the definition for the builder's "Tested" badge + activity feed.
        form.setLastTestedAt(now);
        form.setLastTestedBy(userId);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "tested", "Signed off v" + latest.getVersion());
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDelete(@RequestHeader("X-Tenant-Id") String tenantId,
                           @RequestHeader(value = "X-User-Id", required = false) String userId,
                           @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        form.setDeletedAt(java.time.LocalDateTime.now());
        forms.save(form);
        record(form, userId, "deleted", null);
    }

    @PostMapping("/{id}/restore")
    public FormDefinition restore(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable String id) {
        FormDefinition form = forms.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown form: " + id));
        form.setDeletedAt(null);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "restored", null);
        return saved;
    }

    @DeleteMapping("/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresCapabilityAction(CapabilityLevel.Action.DELETE)
    @Transactional // #62: versions + audit + form deletes are atomic (no orphaned child rows).
    public void purge(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        FormDefinition form = forms.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown form: " + id));
        versions.deleteAll(versions.findByFormIdOrderByVersionAsc(id));
        audit.deleteByFormId(id);
        forms.delete(form);
    }

    /* ── edit lock (advisory) ───────────────────────────────── */

    /** Acquire the edit lock. 409 if another user holds a fresh lock unless {@code force=true}. */
    @PostMapping("/{id}/checkout")
    public FormDefinition checkout(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestHeader(value = "X-User-Id", required = false) String userId,
                                   @PathVariable String id,
                                   @RequestParam(defaultValue = "false") boolean force) {
        FormDefinition form = load(tenantId, id);
        String holder = form.getLockedBy();
        if (holder != null && !holder.equals(userId) && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Locked by " + holder);
        }
        boolean takeover = holder != null && !holder.equals(userId);
        form.setLockedBy(userId);
        form.setLockedAt(java.time.LocalDateTime.now());
        FormDefinition saved = forms.save(form);
        record(saved, userId, "checked_out", takeover ? "took over from " + holder : null);
        return saved;
    }

    /** Release the edit lock (only the holder, or {@code force=true}). */
    @PostMapping("/{id}/release")
    public FormDefinition release(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable String id,
                                  @RequestParam(defaultValue = "false") boolean force) {
        FormDefinition form = load(tenantId, id);
        if (force || userId == null || userId.equals(form.getLockedBy())) {
            form.setLockedBy(null);
            form.setLockedAt(null);
            return forms.save(form);
        }
        return form;
    }

    /** Discard the working draft — revert it to the published (else latest) version — and release the lock. */
    @PostMapping("/{id}/discard")
    public FormDefinition discard(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        Integer pin = form.getPublishedVersion();
        String reverted = pin != null
                ? version(form, pin).getSchema()
                : versions.findTopByFormIdOrderByVersionDesc(id).map(FormVersion::getSchema).orElse(null);
        form.setDraftSchema(reverted);
        form.setLockedBy(null);
        form.setLockedAt(null);
        form.setUpdatedBy(userId);
        FormDefinition saved = forms.save(form);
        record(saved, userId, "discarded", null);
        return saved;
    }

    /* ── clone ──────────────────────────────────────────────── */

    /** Duplicate a form as a new DRAFT ("Copy of …"), copying the current draft schema; no versions carried over. */
    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public FormDefinition clone(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @PathVariable String id) {
        FormDefinition src = load(tenantId, id);
        FormDefinition copy = new FormDefinition();
        copy.setTenantId(tenantId);
        copy.setCode(uniqueCode(tenantId));
        copy.setName("Copy of " + src.getName());
        copy.setDescription(src.getDescription());
        copy.setStatus("DRAFT");
        copy.setDraftSchema(src.getDraftSchema());
        copy.setCreatedBy(userId);
        copy.setUpdatedBy(userId);
        FormDefinition saved = forms.save(copy);
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
        List<FormAuditEvent> events = audit.findByFormIdAndTenantIdOrderByAtDesc(id, tenantId);
        Map<String, String> names = userDirectory.namesFor(events.stream().map(FormAuditEvent::getActor).toList());
        return events.stream()
                .map(e -> new AuditView(e.getAction(), e.getActor(),
                        e.getActor() != null ? names.getOrDefault(e.getActor(), e.getActor()) : null,
                        e.getDetail(), e.getAt()))
                .toList();
    }

    /* ── helpers ────────────────────────────────────────────── */

    /** Read-time enrichment: fill createdByName/updatedByName on one form. */
    private FormDefinition withNames(FormDefinition form) {
        withNames(List.of(form));
        return form;
    }

    /** Read-time enrichment: batch-fill createdByName/updatedByName across forms in ONE lookup, plus the
     *  per-tenant branding lock (one plan lookup per distinct tenant, not per form). */
    private List<FormDefinition> withNames(List<FormDefinition> list) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (FormDefinition f : list) {
            if (f.getCreatedBy() != null) ids.add(f.getCreatedBy());
            if (f.getUpdatedBy() != null) ids.add(f.getUpdatedBy());
        }
        Map<String, String> names = userDirectory.namesFor(ids);
        Map<String, Boolean> canHide = new java.util.HashMap<>();
        for (FormDefinition f : list) {
            if (f.getCreatedBy() != null) f.setCreatedByName(names.getOrDefault(f.getCreatedBy(), f.getCreatedBy()));
            if (f.getUpdatedBy() != null) f.setUpdatedByName(names.getOrDefault(f.getUpdatedBy(), f.getUpdatedBy()));
            f.setBrandingLocked(!canHide.computeIfAbsent(f.getTenantId(), branding::canHideBadge));
        }
        return list;
    }

    /** Read-time enrichment for a SINGLE form response that doesn't need display names: tells the client
     *  whether the tenant's plan lets it hide the Lukeflow badge. (Un-enriched responses stay locked —
     *  see {@link FormDefinition#isBrandingLocked()} — so this only ever relaxes a conservative default.) */
    private FormDefinition withBrandingLock(FormDefinition form) {
        form.setBrandingLocked(!branding.canHideBadge(form.getTenantId()));
        return form;
    }

    /** Append an immutable audit event for a lifecycle action on {@code form}. */
    private void record(FormDefinition form, String actor, String action, String detail) {
        audit.save(new FormAuditEvent(form.getId(), form.getTenantId(), action, detail, actor));
    }

    private FormDefinition load(String tenantId, String id) {
        requireTenant(tenantId);
        FormDefinition form = forms.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown form: " + id));
        if (form.getDeletedAt() != null) throw notFound("Form is in trash: " + id);
        return form;
    }

    private FormDefinition loadByCode(String tenantId, String code) {
        requireTenant(tenantId);
        FormDefinition form = forms.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> notFound("Unknown form code: " + code));
        if (form.getDeletedAt() != null) throw notFound("Form is in trash: " + code);
        return form;
    }

    private FormVersion version(FormDefinition form, int v) {
        return versions.findByFormIdAndVersion(form.getId(), v)
                .orElseThrow(() -> notFound("Unknown version v" + v + " for " + form.getCode()));
    }

    private String uniqueCode(String tenantId) {
        String code = FormSupport.generateCode();
        while (forms.existsByTenantIdAndCode(tenantId, code)) code = FormSupport.generateCode();
        return code;
    }

    private int parsePin(String pin) {
        try {
            return Integer.parseInt(pin.startsWith("v") ? pin.substring(1) : pin);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pin: " + pin + " (use published|latest|draft|v{n})");
        }
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
