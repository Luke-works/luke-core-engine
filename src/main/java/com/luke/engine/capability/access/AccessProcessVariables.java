package com.luke.engine.capability.access;

/**
 * The variable contract between {@code AccessRequestApprovalProcess.bpmn} and the Java that
 * starts, decides and fulfils it. One place to change a name, so the BPMN expressions, the
 * delegates and the controller cannot drift apart silently — a mistyped variable name in a
 * process is otherwise only discovered at runtime, as a task that routes nowhere.
 */
public final class AccessProcessVariables {

    private AccessProcessVariables() {}

    /* ── set at start ─────────────────────────────────────────── */

    /** Id of the {@link AccessRequest} row this instance orchestrates. */
    public static final String ACCESS_REQUEST_ID = "accessRequestId";
    public static final String TENANT_ID = "tenantId";
    /** Engine userId of the requester — the assignee of the rework task. */
    public static final String REQUESTER_ID = "requesterId";
    public static final String CAPABILITY_CODE = "capabilityCode";
    public static final String REQUESTED_LEVEL = "requestedLevel";
    /** Candidate group the approval task routes to (see {@code CapabilityOwnership}). */
    public static final String APPROVER_GROUP = "approverGroup";

    /* ── set when the approval task is completed ──────────────── */

    /** Boolean: the owner's decision. */
    public static final String APPROVED = "approved";
    /** Level actually granted, which may differ from {@link #REQUESTED_LEVEL}. */
    public static final String GRANT_LEVEL = "grantLevel";
    /** Reason for the decision — shown to the requester when the request comes back. */
    public static final String DECISION_NOTE = "decisionNote";
    /** Engine userId of the owner who decided. */
    public static final String DECIDED_BY = "decidedBy";

    /* ── set when the rework task is completed ────────────────── */

    /** Boolean: true = revise and try again, false = withdraw. */
    public static final String RESUBMIT = "resubmit";

    /* ── service-task input mapping ───────────────────────────── */

    /** Local input variable naming the status {@code accessRequestStateDelegate} should write. */
    public static final String TARGET_STATE = "targetState";
}
