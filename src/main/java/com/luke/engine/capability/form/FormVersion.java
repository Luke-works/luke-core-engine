package com.luke.engine.capability.form;

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
 * An immutable, checked-in version of a {@link FormDefinition} — the artifact a
 * renderer or process resolves and never changes underneath them. Created by
 * "check in / compile"; never updated.
 */
@Entity
@Table(
    name = "luke_form_versions",
    uniqueConstraints = @UniqueConstraint(name = "uq_formversion_form_version", columnNames = {"formId", "version"}),
    indexes = @Index(name = "idx_formversion_form", columnList = "formId")
)
public class FormVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** References {@link FormDefinition#getId()}. */
    @Column(nullable = false)
    private String formId;

    @Column(nullable = false)
    private int version;

    @Column(columnDefinition = "text", nullable = false)
    private String schema;

    private String checkedInBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime checkedInAt = LocalDateTime.now();

    public FormVersion() {}

    public FormVersion(String formId, int version, String schema, String checkedInBy) {
        this.formId = formId;
        this.version = version;
        this.schema = schema;
        this.checkedInBy = checkedInBy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFormId() { return formId; }
    public void setFormId(String formId) { this.formId = formId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getCheckedInBy() { return checkedInBy; }
    public void setCheckedInBy(String checkedInBy) { this.checkedInBy = checkedInBy; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }
}
