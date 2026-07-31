package com.luke.engine.capability.form;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/**
 * A form template (the "definition"), versioned and tenant-scoped. The editable
 * working draft lives in {@code draftSchema}; immutable checked-in versions are
 * {@link FormVersion} rows. {@code publishedVersion} is the one consumers (the
 * renderer, a process via formKey) resolve to.
 *
 * <p>Addressed externally by {@code code} (FM-XXXX-DDMMMYY), stable across
 * environments; {@code id} is internal. Lives in the capability engine's own
 * schema alongside {@link com.luke.engine.capability.capability.Capability}.
 */
@Entity
@Table(
    name = "luke_form_definitions",
    uniqueConstraints = @UniqueConstraint(name = "uq_form_tenant_code", columnNames = {"tenantId", "code"}),
    indexes = @Index(name = "idx_form_tenant", columnList = "tenantId")
)
public class FormDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Optimistic-locking token (#57): concurrent draft saves / lock checkouts that
     *  read-then-write the same row collide → OptimisticLockException → 409, instead of
     *  a silent last-write-wins clobber. JPA manages it. */
    @Version
    private Long version;

    @Column(nullable = false)
    private String tenantId;

    /** Stable human id, e.g. "FM-XKQW-05JUN26". Unique per tenant. */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Authoring intent. INBOUND = embedded somewhere, anyone submits; OUTBOUND = prefilled by a
     *  preparer and sent to a named recipient (never embeddable). Chosen at creation. */
    public static final String KIND_INBOUND = "INBOUND";
    public static final String KIND_OUTBOUND = "OUTBOUND";

    @Column(nullable = false)
    private String kind = KIND_INBOUND;

    /** Inbound only: the chosen submission handling (e.g. {@code "COLLECT"}). Null = undecided, which
     *  keeps the embed surface gated ("decide what happens to submissions before embedding"). */
    private String submissionHandling;

    /** Outbound only: JSON map of {@code fieldKey → role} where role ∈ PREPARER | RECIPIENT | EITHER.
     *  Recipient identity (firstName/lastName/email) is always preparer-provided and implicit. */
    @Column(columnDefinition = "text")
    private String outboundRolesJson;

    /** Lifecycle: DRAFT, PUBLISHED, RETIRED. */
    @Column(nullable = false)
    private String status = "DRAFT";

    /** The version consumers resolve to via {@code @published}; null until first publish. */
    private Integer publishedVersion;

    /** Editable working draft (coltorapps schema JSON). Not what processes consume. */
    @Column(columnDefinition = "text")
    private String draftSchema;

    /** Per-form allowlist of web origins permitted to embed the published form, enforced as the CSP
     *  {@code frame-ancestors} directive on the public embed surface (Route B M2). Comma-separated
     *  origins; null/empty = any site may embed (public default). Sanitized via {@link FrameAncestors}. */
    @Column(columnDefinition = "text")
    private String allowedEmbedOrigins;

    /** Friendly LABELS for the origins above, as a JSON object keyed by canonical origin — see
     *  {@link EmbedSiteNames}. Purely presentational: kept out of {@link #allowedEmbedOrigins} so a
     *  label can never alter the CSP the embed surface enforces. Null = no labels. */
    @Column(columnDefinition = "text")
    private String embedOriginNames;

    /** Which version the PUBLIC EMBED serves.
     *
     *  <p>{@link #EMBED_MODE_AUTO} (the default, and how embeds have always behaved) resolves
     *  {@code publishedVersion} on every request — so publishing reaches every live embed immediately,
     *  with no change on the embedding website. {@link #EMBED_MODE_PINNED} serves {@link #embedVersion}
     *  instead, so publishing does NOT change what fillers see until someone explicitly updates the
     *  embed. Pinning is what makes a legally-significant form safe to iterate on.
     *
     *  <p>Both the public RENDER and the public SUBMIT resolve through {@link EmbedVersions#resolve},
     *  so the schema a filler sees is always the schema their answers are validated against. */
    public static final String EMBED_MODE_AUTO = "AUTO";
    public static final String EMBED_MODE_PINNED = "PINNED";

    @Column(nullable = false)
    private String embedVersionMode = EMBED_MODE_AUTO;

    /** PINNED only: the version embeds serve. Null (or a version that no longer exists) falls back to
     *  {@code publishedVersion} — an embed must never go dark because of a stale pin. */
    private Integer embedVersion;

    /** Embed-key version for revocation (Route B M4): the signed embed token carries this value;
     *  bumping it invalidates every previously-issued token for this form (they fail the version check
     *  on the public embed surface). Starts at 0. */
    @Column(nullable = false)
    private int embedKeyVersion = 0;

    /** Show the "Developed at Lukeflow" badge on this form's PUBLIC surfaces (embed iframe, outbound
     *  respond page, recipient portal). On by default — it is our attribution. Free-plan tenants can't
     *  turn it off: {@link com.luke.engine.branding.BrandingPolicy} forces it on regardless of this
     *  value, so the stored preference is only honoured while the tenant pays. Presentation chrome, NOT
     *  part of the data contract, so it lives here (like {@code allowedEmbedOrigins}) rather than in the
     *  versioned schema — flipping it takes effect on the live embed with no re-publish. */
    @Column(nullable = false)
    private boolean showBranding = true;

    /** Soft-delete marker (trash); null = live. */
    private LocalDateTime deletedAt;

    /** Advisory edit-lock holder (userId), or null when unlocked. Set on checkout,
     *  cleared on release / check-in / discard. Stale locks can be taken over. */
    private String lockedBy;
    private LocalDateTime lockedAt;

    private String createdBy;
    private String updatedBy;

    /** Resolved display names for created_by / updated_by — filled at read time, NOT persisted. */
    @Transient
    private String createdByName;
    @Transient
    private String updatedByName;

    /** True when the tenant's plan does NOT allow hiding the Lukeflow badge (free tier), so the
     *  builder renders the option as locked with an upgrade hint. Filled at read time, NOT persisted.
     *  Defaults to LOCKED so any response that skips the enrichment fails closed — a stale {@code false}
     *  would offer a toggle the server then rejects. */
    @Transient
    private boolean brandingLocked = true;

    /** When the form last passed its self-test ("Test the form"), and by whom. */
    private LocalDateTime lastTestedAt;
    private String lastTestedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public FormDefinition() {}

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getSubmissionHandling() { return submissionHandling; }
    public void setSubmissionHandling(String submissionHandling) { this.submissionHandling = submissionHandling; }

    public String getOutboundRolesJson() { return outboundRolesJson; }
    public void setOutboundRolesJson(String outboundRolesJson) { this.outboundRolesJson = outboundRolesJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(Integer publishedVersion) { this.publishedVersion = publishedVersion; }

    public String getDraftSchema() { return draftSchema; }
    public void setDraftSchema(String draftSchema) { this.draftSchema = draftSchema; }

    public String getAllowedEmbedOrigins() { return allowedEmbedOrigins; }
    public void setAllowedEmbedOrigins(String allowedEmbedOrigins) { this.allowedEmbedOrigins = allowedEmbedOrigins; }

    public String getEmbedOriginNames() { return embedOriginNames; }
    public void setEmbedOriginNames(String embedOriginNames) { this.embedOriginNames = embedOriginNames; }

    public int getEmbedKeyVersion() { return embedKeyVersion; }
    public void setEmbedKeyVersion(int embedKeyVersion) { this.embedKeyVersion = embedKeyVersion; }

    public String getEmbedVersionMode() { return embedVersionMode; }
    public void setEmbedVersionMode(String embedVersionMode) { this.embedVersionMode = embedVersionMode; }

    public Integer getEmbedVersion() { return embedVersion; }
    public void setEmbedVersion(Integer embedVersion) { this.embedVersion = embedVersion; }

    public boolean isShowBranding() { return showBranding; }
    public void setShowBranding(boolean showBranding) { this.showBranding = showBranding; }

    public boolean isBrandingLocked() { return brandingLocked; }
    public void setBrandingLocked(boolean brandingLocked) { this.brandingLocked = brandingLocked; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }

    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }

    public LocalDateTime getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(LocalDateTime lastTestedAt) { this.lastTestedAt = lastTestedAt; }

    public String getLastTestedBy() { return lastTestedBy; }
    public void setLastTestedBy(String lastTestedBy) { this.lastTestedBy = lastTestedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
