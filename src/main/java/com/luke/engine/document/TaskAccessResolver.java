package com.luke.engine.document;

/**
 * The CONTEXT layer of document authZ (DOC-4): given a document's process/task, can this user see it?
 * Pluggable so the group source can change without touching {@link DocumentAccessGuard}.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code taskId} set  → allow iff the user is the task's assignee, a candidate user, or a member
 *       of one of its Camunda CANDIDATE GROUPS.</li>
 *   <li>{@code taskId} null → allow iff the user is a PARTICIPANT of the process instance
 *       (initiator, or a member of any candidate group used on the process).</li>
 * </ul>
 *
 * <p>V1 default is {@link AllowAllTaskAccessResolver} (the tenant + capability layers still apply);
 * the real Camunda-candidate-group impl lands in DOC-4.
 */
public interface TaskAccessResolver {

    /** True if {@code userId} may access {@code doc} given its process/task context. */
    boolean canAccess(String tenantId, String userId, Document doc);

    /** Upload-time variant: no Document row exists yet, so check the requested process/task directly. */
    boolean canUpload(String tenantId, String userId, String processRef,
                      String processInstanceId, String taskId);
}
