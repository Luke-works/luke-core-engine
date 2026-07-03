package com.luke.engine.workflow;

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
 * An immutable, checked-in snapshot of a {@link WorkflowDefinition} — the artifact
 * that gets signed off and deployed. Created by check-in and never updated (except
 * for the sign-off stamp). Check-in compiles best-effort: {@code compileOk=false}
 * with a stored {@code compileError} is a valid snapshot (errors never block
 * check-in), but sign-off and publish require a clean compile.
 */
@Entity
@Table(
    name = "luke_workflow_versions",
    uniqueConstraints = @UniqueConstraint(name = "uq_wfversion_def_version", columnNames = {"definitionId", "version"}),
    indexes = @Index(name = "idx_wfversion_def", columnList = "definitionId")
)
public class WorkflowVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** References {@link WorkflowDefinition#getId()}. */
    @Column(nullable = false)
    private String definitionId;

    @Column(nullable = false)
    private int version;

    @Column(columnDefinition = "text", nullable = false)
    private String jsonSource;

    /** The compiled BPMN, or null when the snapshot didn't compile. */
    @Column(columnDefinition = "text")
    private String bpmnXml;

    private String processId;

    @Column(nullable = false)
    private boolean compileOk;

    @Column(columnDefinition = "text")
    private String compileError;

    private String checkedInBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime checkedInAt = LocalDateTime.now();

    /** When this version passed sign-off (the "tested" gate for publish), and by whom. */
    private LocalDateTime signedOffAt;
    private String signedOffBy;

    public WorkflowVersion() {}

    public WorkflowVersion(String definitionId, int version, String jsonSource, String checkedInBy) {
        this.definitionId = definitionId;
        this.version = version;
        this.jsonSource = jsonSource;
        this.checkedInBy = checkedInBy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDefinitionId() { return definitionId; }
    public void setDefinitionId(String definitionId) { this.definitionId = definitionId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getJsonSource() { return jsonSource; }
    public void setJsonSource(String jsonSource) { this.jsonSource = jsonSource; }

    public String getBpmnXml() { return bpmnXml; }
    public void setBpmnXml(String bpmnXml) { this.bpmnXml = bpmnXml; }

    public String getProcessId() { return processId; }
    public void setProcessId(String processId) { this.processId = processId; }

    public boolean isCompileOk() { return compileOk; }
    public void setCompileOk(boolean compileOk) { this.compileOk = compileOk; }

    public String getCompileError() { return compileError; }
    public void setCompileError(String compileError) { this.compileError = compileError; }

    public String getCheckedInBy() { return checkedInBy; }
    public void setCheckedInBy(String checkedInBy) { this.checkedInBy = checkedInBy; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }

    public LocalDateTime getSignedOffAt() { return signedOffAt; }
    public void setSignedOffAt(LocalDateTime signedOffAt) { this.signedOffAt = signedOffAt; }

    public String getSignedOffBy() { return signedOffBy; }
    public void setSignedOffBy(String signedOffBy) { this.signedOffBy = signedOffBy; }
}
