package com.luke.engine.document;

import java.util.List;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Mirrors {@link Document} rows into Camunda as native attachments ("coredev" Tasklist/Cockpit view),
 * classified TASK vs PROCESS by {@link DocumentProcessLink}. Bridges the id gap: a form's documents are
 * tracked by the FORM instance id (their {@code ownerEntityId}), while Camunda needs the real
 * {@code processInstanceId}, which only exists once the intake process has started.
 *
 * <p>Two entry points, both best-effort (the {@code luke_document} table is the system of record — a
 * Camunda mirror failure must never break submit/upload):
 * <ul>
 *   <li>{@link #mirrorFormProcess} — at process start, stamp the real pid onto the form's READY
 *       attachments and mirror them as PROCESS attachments;</li>
 *   <li>{@link #mirrorTaskAttachment} — when a reviewer uploads against a live task, resolve that task's
 *       pid and mirror the document as a TASK attachment.</li>
 * </ul>
 */
@Component
public class DocumentCamundaMirror {

    private static final Logger log = LoggerFactory.getLogger(DocumentCamundaMirror.class);
    private static final String FORMS_CAPABILITY = "FORMS";

    private final DocumentRepository repo;
    private final DocumentProcessLink link;
    private final TaskService taskService;

    public DocumentCamundaMirror(DocumentRepository repo, DocumentProcessLink link, TaskService taskService) {
        this.repo = repo;
        this.link = link;
        this.taskService = taskService;
    }

    /**
     * At process start: bind the form's READY attachments (owned by {@code formInstanceId}) to the real
     * Camunda {@code processInstanceId} and mirror each into Camunda. Process-level (no taskId) → PROCESS
     * attachments. Returns the number mirrored. Never throws.
     */
    @Transactional
    public int mirrorFormProcess(String tenantId, String formInstanceId, String processInstanceId) {
        if (!StringUtils.hasText(formInstanceId) || !StringUtils.hasText(processInstanceId)) return 0;
        int n = 0;
        try {
            List<Document> rows = repo.findByTenantIdAndCapabilityAndOwnerEntityIdOrderByCreatedAtDesc(
                    tenantId, FORMS_CAPABILITY, formInstanceId);
            for (Document d : rows) {
                if (!Document.STATUS_READY.equals(d.getStatus())) continue;
                if (!processInstanceId.equals(d.getProcessInstanceId())) {
                    d.setProcessInstanceId(processInstanceId);
                    repo.save(d);
                }
                link.attach(d);   // best-effort inside; PROCESS-classified (taskId null on fill uploads)
                n++;
            }
        } catch (RuntimeException e) {
            log.debug("mirrorFormProcess for instance {} (pid {}) failed (ignored): {}",
                    formInstanceId, processInstanceId, e.toString());
        }
        return n;
    }

    /**
     * When a task-scoped document is finalized (a reviewer attached it to a live task), resolve that
     * task's running process instance, stamp it onto the row, and mirror the document as a TASK
     * attachment. No-op for a document with no taskId or no live task. Never throws.
     */
    @Transactional
    public void mirrorTaskAttachment(String tenantId, String docId) {
        try {
            Document d = repo.findByIdAndTenantId(docId, tenantId).orElse(null);
            if (d == null || !StringUtils.hasText(d.getTaskId())) return;
            if (!Document.STATUS_READY.equals(d.getStatus())) return;
            if (!StringUtils.hasText(d.getProcessInstanceId())) {
                Task t = taskService.createTaskQuery().taskId(d.getTaskId()).singleResult();
                if (t == null || !StringUtils.hasText(t.getProcessInstanceId())) return; // task gone/completed
                d.setProcessInstanceId(t.getProcessInstanceId());
                repo.save(d);
            }
            link.attach(d);   // TASK-classified (taskId present)
        } catch (RuntimeException e) {
            log.debug("mirrorTaskAttachment for document {} failed (ignored): {}", docId, e.toString());
        }
    }
}
