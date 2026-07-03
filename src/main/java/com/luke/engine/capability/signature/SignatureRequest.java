package com.luke.engine.capability.signature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One e-signature request: a tenant's source PDF, the single signer it is sent to, the
 * field where the signature is placed, and the lifecycle state. Tenant-scoped; addressed
 * externally by {@code code} (SR-XXXX-DDMMMYY) and, for the public signer, by an
 * unguessable {@code signToken}.
 *
 * <p><b>No PDF bytes live here.</b> Source and signed PDFs are held in the
 * {@link DocumentStore}; this row keeps only their object keys + SHA-256 + size, plus a
 * {@code retainUntil} retention date.
 *
 * <p>Mirrors {@code luke-core-engine} entity style (plain JPA, UUID id, LocalDateTime +
 * {@code @PreUpdate}). Kept DB-PORTABLE (no native SQL / Postgres-only DDL; enums stored
 * as strings) for a clean H2&rarr;Postgres swap at SIG-M.
 */
@Entity
@Table(
    name = "luke_signature_requests",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_sigreq_tenant_code", columnNames = {"tenantId", "code"}),
        @UniqueConstraint(name = "uq_sigreq_sign_token", columnNames = {"signToken"})
    },
    indexes = {
        @Index(name = "idx_sigreq_tenant_status", columnList = "tenantId,status"),
        @Index(name = "idx_sigreq_retain", columnList = "retainUntil")
    }
)
public class SignatureRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Optimistic-locking token: concurrent send/void/sign that read-then-write the same
     *  row collide → OptimisticLockException (409) instead of a silent clobber. */
    @Version
    private Long version;

    @Column(nullable = false)
    private String tenantId;

    /** Stable human id, e.g. "SR-XKQW-19JUN26". Unique per tenant. */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    /** Lifecycle status: DRAFT → SENT → VIEWED → COMPLETED, or → VOIDED. The act of signing is
     *  recorded as the SIGNED audit ACTION (atomic with the flip to COMPLETED); there is no
     *  separately-persisted SIGNED status in V1. */
    @Column(nullable = false)
    private String status = "DRAFT";

    // ── Signer (set by the requester; the signer can NEVER change these) ──────────
    @Column(nullable = false)
    private String signerEmail;

    @Column(nullable = false)
    private String signerName;

    /** Only used when verificationMethod requires a phone (SMS_OTP); else null. */
    private String signerPhone;

    /** How the signer must verify before signing. Requester-set, signer-immutable.
     *  Pinned to VARCHAR so the column type is identical on H2 and Postgres (no native enum). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private VerificationMethod verificationMethod = VerificationMethod.NONE;

    // ── Signature field placement (UI coordinates; one field in V1) ───────────────
    @Column(nullable = false)
    private int fieldPage;
    // Explicit snake_case column names: Hibernate maps a single trailing capital (fieldX) to
    // "fieldx" (NO underscore), but V6__signature_tables.sql created these as field_x/_y/_w/_h — so
    // without these the generated SQL references a non-existent column and 500s on Postgres. (H2
    // tests pass because ddl-auto creates the schema from the entity, masking the mismatch.)
    @Column(name = "field_x", nullable = false)
    private double fieldX;
    @Column(name = "field_y", nullable = false)
    private double fieldY;
    @Column(name = "field_w", nullable = false)
    private double fieldW;
    @Column(name = "field_h", nullable = false)
    private double fieldH;

    /** Unguessable public-signing token; minted on send, null until then. Unique. */
    private String signToken;

    // ── Document references (bytes live in the DocumentStore, never the DB) ───────
    private String sourceObjectKey;
    private String signedObjectKey;
    private String sourceSha256;
    private String signedSha256;
    private Long sizeBytes;

    /** Retention boundary (now + LUKE_SIGN_RETENTION_DAYS at create). After this the
     *  documents may be physically purged (SIG-8); the metadata row is kept. */
    private LocalDateTime retainUntil;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime sentAt;
    private LocalDateTime signedAt;
    private LocalDateTime updatedAt;

    /** Resolved display name for created_by — filled at read time, NOT persisted. */
    @Transient
    private String createdByName;

    public SignatureRequest() {}

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSignerEmail() { return signerEmail; }
    public void setSignerEmail(String signerEmail) { this.signerEmail = signerEmail; }

    public String getSignerName() { return signerName; }
    public void setSignerName(String signerName) { this.signerName = signerName; }

    public String getSignerPhone() { return signerPhone; }
    public void setSignerPhone(String signerPhone) { this.signerPhone = signerPhone; }

    public VerificationMethod getVerificationMethod() { return verificationMethod; }
    public void setVerificationMethod(VerificationMethod verificationMethod) { this.verificationMethod = verificationMethod; }

    public int getFieldPage() { return fieldPage; }
    public void setFieldPage(int fieldPage) { this.fieldPage = fieldPage; }

    public double getFieldX() { return fieldX; }
    public void setFieldX(double fieldX) { this.fieldX = fieldX; }

    public double getFieldY() { return fieldY; }
    public void setFieldY(double fieldY) { this.fieldY = fieldY; }

    public double getFieldW() { return fieldW; }
    public void setFieldW(double fieldW) { this.fieldW = fieldW; }

    public double getFieldH() { return fieldH; }
    public void setFieldH(double fieldH) { this.fieldH = fieldH; }

    public String getSignToken() { return signToken; }
    public void setSignToken(String signToken) { this.signToken = signToken; }

    public String getSourceObjectKey() { return sourceObjectKey; }
    public void setSourceObjectKey(String sourceObjectKey) { this.sourceObjectKey = sourceObjectKey; }

    public String getSignedObjectKey() { return signedObjectKey; }
    public void setSignedObjectKey(String signedObjectKey) { this.signedObjectKey = signedObjectKey; }

    public String getSourceSha256() { return sourceSha256; }
    public void setSourceSha256(String sourceSha256) { this.sourceSha256 = sourceSha256; }

    public String getSignedSha256() { return signedSha256; }
    public void setSignedSha256(String signedSha256) { this.signedSha256 = signedSha256; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public LocalDateTime getRetainUntil() { return retainUntil; }
    public void setRetainUntil(LocalDateTime retainUntil) { this.retainUntil = retainUntil; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public LocalDateTime getSignedAt() { return signedAt; }
    public void setSignedAt(LocalDateTime signedAt) { this.signedAt = signedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
}
