package com.luke.engine.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * What a published workflow LISTENS for — the "start-on-event" registry. When a workflow
 * whose trigger is an event (e.g. {@code forms.submitted}) is published, its trigger is
 * recorded here against the deployed process key; when a matching event arrives, the
 * correlator starts that process.
 *
 * <p>Why a registry rather than a BPMN message start event: Camunda enforces global
 * uniqueness of message-start names across deployed definitions, so two workflows started
 * by the same form event would fail to deploy. The registry makes "who starts on this
 * event" explicit, queryable, and free of that constraint — a plain none-start process is
 * launched by key. Mid-flow waits still use ordinary message correlation.
 *
 * <p>One row per published definition (re-publish replaces it), scoped by tenant. A null
 * {@link #formCode} means "any form of this event type".
 */
@Entity
@Table(
    name = "luke_workflow_trigger_subscription",
    indexes = {
        @Index(name = "idx_wfsub_match", columnList = "tenantId,capability,eventType"),
        @Index(name = "idx_wfsub_def", columnList = "definitionId")
    }
)
public class WorkflowTriggerSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** The workflow definition this subscription belongs to (one active row per definition). */
    @Column(nullable = false)
    private String definitionId;

    /** The deployed Camunda process key to start ({@code <docId>_v<version>}). */
    @Column(nullable = false)
    private String processId;

    @Column(nullable = false)
    private String capability;

    @Column(nullable = false)
    private String eventType;

    /** For forms triggers: the specific form code, or null for "any form". */
    private String formCode;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public WorkflowTriggerSubscription() {}

    public WorkflowTriggerSubscription(String tenantId, String definitionId, String processId,
            String capability, String eventType, String formCode, int version) {
        this.tenantId = tenantId;
        this.definitionId = definitionId;
        this.processId = processId;
        this.capability = capability;
        this.eventType = eventType;
        this.formCode = formCode;
        this.version = version;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getDefinitionId() { return definitionId; }
    public String getProcessId() { return processId; }
    public String getCapability() { return capability; }
    public String getEventType() { return eventType; }
    public String getFormCode() { return formCode; }
    public int getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
