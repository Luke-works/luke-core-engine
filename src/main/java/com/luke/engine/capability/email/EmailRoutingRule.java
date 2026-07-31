package com.luke.engine.capability.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One routing rule: a test applied to an arriving email, and what to do when it matches.
 *
 * <p>Without rules every inbound message produces the same unassigned "Review inbound email"
 * task, which is a queue rather than a workflow — someone still has to read each one and decide
 * where it belongs. A rule moves that decision to receipt time: invoices go to finance at high
 * priority, bounce notifications never become a task at all, a support address starts the
 * support process instead of the generic review.
 *
 * <p><b>Ordered, first match wins.</b> Rules for a tenant are evaluated by {@link #sortOrder}
 * and the first one that matches decides the outcome; later rules do not accumulate on top of
 * it. Overlapping rules are the norm ("from billing@" and "subject contains invoice"), and
 * merging their actions would make the result depend on rule order in a way nobody can predict
 * from reading any single row. First-match is the behaviour every mail client has trained users
 * to expect.
 *
 * <p>A null {@link #boxId} applies the rule to every inbound box the tenant has.
 */
@Entity
@Table(
    name = "luke_email_routing_rules",
    indexes = {
        @Index(name = "idx_emailrule_tenant", columnList = "tenantId"),
        @Index(name = "idx_emailrule_order", columnList = "tenantId,boxId,sortOrder")
    }
)
public class EmailRoutingRule {

    /** Which part of the message the rule tests. */
    public enum Field { FROM, SUBJECT, TO, BODY, ANY }

    /** How {@link #matchValue} is compared against that part. */
    public enum Operator { CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, REGEX }

    @Id
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** The inbound box this rule is scoped to; null = all inbound boxes. */
    private String boxId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Evaluation order, ascending. Named {@code sortOrder} because {@code position} is a SQL
     *  standard function name that H2 rejects as an unquoted column. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 32)
    private String matchField = Field.SUBJECT.name();

    @Column(nullable = false, length = 32)
    private String matchOperator = Operator.CONTAINS.name();

    @Column(nullable = false, length = 1000)
    private String matchValue;

    @Column(nullable = false)
    private boolean caseSensitive;

    // ── actions ──────────────────────────────────────────────────────────────

    /** Assign the review task directly to this user id. */
    private String actionAssignee;

    /** Offer the review task to this Camunda candidate group. */
    private String actionCandidateGroup;

    /** Camunda task priority (higher sorts first in a tasklist). */
    private Integer actionPriority;

    /** Start this process instead of the default review process. */
    private String actionProcessKey;

    /** Override the task name (default: "Review: &lt;subject&gt;"). */
    private String actionTaskName;

    /** Store the message but create no task at all — for noise (bounces, auto-replies). */
    @Column(nullable = false)
    private boolean actionSuppressTask;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public EmailRoutingRule() {}

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBoxId() { return boxId; }
    public void setBoxId(String boxId) { this.boxId = boxId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getMatchField() { return matchField; }
    public void setMatchField(String matchField) { this.matchField = matchField; }

    public String getMatchOperator() { return matchOperator; }
    public void setMatchOperator(String matchOperator) { this.matchOperator = matchOperator; }

    public String getMatchValue() { return matchValue; }
    public void setMatchValue(String matchValue) { this.matchValue = matchValue; }

    public boolean isCaseSensitive() { return caseSensitive; }
    public void setCaseSensitive(boolean caseSensitive) { this.caseSensitive = caseSensitive; }

    public String getActionAssignee() { return actionAssignee; }
    public void setActionAssignee(String actionAssignee) { this.actionAssignee = actionAssignee; }

    public String getActionCandidateGroup() { return actionCandidateGroup; }
    public void setActionCandidateGroup(String actionCandidateGroup) { this.actionCandidateGroup = actionCandidateGroup; }

    public Integer getActionPriority() { return actionPriority; }
    public void setActionPriority(Integer actionPriority) { this.actionPriority = actionPriority; }

    public String getActionProcessKey() { return actionProcessKey; }
    public void setActionProcessKey(String actionProcessKey) { this.actionProcessKey = actionProcessKey; }

    public String getActionTaskName() { return actionTaskName; }
    public void setActionTaskName(String actionTaskName) { this.actionTaskName = actionTaskName; }

    public boolean isActionSuppressTask() { return actionSuppressTask; }
    public void setActionSuppressTask(boolean actionSuppressTask) { this.actionSuppressTask = actionSuppressTask; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
