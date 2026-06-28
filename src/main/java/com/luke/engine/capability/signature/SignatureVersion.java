package com.luke.engine.capability.signature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * An immutable checked-in snapshot of a {@link SignatureDefinition}'s schema (mirrors
 * {@code FormVersion}). Versions are monotonic per definition. {@code signedOffAt} records the
 * legal review pass and gates publishing — a version is never mutated after check-in except to
 * stamp the sign-off. No {@code @Version} (immutable, so no optimistic-lock token needed).
 */
@Entity
@Table(
    name = "luke_signature_versions",
    uniqueConstraints = @UniqueConstraint(name = "uq_sigver_def_version", columnNames = {"definitionId", "version"}),
    indexes = @Index(name = "idx_sigver_def", columnList = "definitionId,version")
)
public class SignatureVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String definitionId;

    @Column(nullable = false)
    private int version;

    /** Immutable schema artifact (SignatureSchema JSON). */
    @Column(columnDefinition = "text")
    private String schema;

    private String checkedInBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime checkedInAt = LocalDateTime.now();

    /** Legal sign-off — null until reviewed; gates publishing. */
    private LocalDateTime signedOffAt;
    private String signedOffBy;

    public SignatureVersion() {}

    public SignatureVersion(String definitionId, int version, String schema, String checkedInBy) {
        this.definitionId = definitionId;
        this.version = version;
        this.schema = schema;
        this.checkedInBy = checkedInBy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDefinitionId() { return definitionId; }
    public void setDefinitionId(String definitionId) { this.definitionId = definitionId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getCheckedInBy() { return checkedInBy; }
    public void setCheckedInBy(String checkedInBy) { this.checkedInBy = checkedInBy; }
    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(LocalDateTime checkedInAt) { this.checkedInAt = checkedInAt; }
    public LocalDateTime getSignedOffAt() { return signedOffAt; }
    public void setSignedOffAt(LocalDateTime signedOffAt) { this.signedOffAt = signedOffAt; }
    public String getSignedOffBy() { return signedOffBy; }
    public void setSignedOffBy(String signedOffBy) { this.signedOffBy = signedOffBy; }
}
