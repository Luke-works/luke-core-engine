package com.luke.engine.document;

import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Attaches a document REFERENCE (never bytes) to a Camunda process/task (DOC-9). Two convenience views,
 * both reference-only:
 * <ul>
 *   <li>a process variable holding the {@code docId} (so a BPMN flow can carry it), and</li>
 *   <li>an {@code ACT_HI_ATTACHMENT} row in <b>URL_ mode</b> — {@code createAttachment(..., url)}, NEVER
 *       the {@code InputStream} overload — so the attachment points at {@code /api/documents/{id}/content}
 *       and Camunda stores no {@code CONTENT_ID_}/{@code ACT_GE_BYTEARRAY} blob.</li>
 * </ul>
 * When the document carries a {@code taskId} the attachment is created against that task (so it surfaces
 * on the task in Tasklist/Cockpit with {@code TASK_ID_} + {@code PROC_INST_ID_}); otherwise it is attached
 * at the process level only. {@code luke_document} remains the system of record — this mirror is
 * purgeable by Camunda history cleanup and is purely a convenience.
 */
@Component
public class DocumentProcessLink {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessLink.class);
    // Attachment "type" doubles as the classification surfaced in Tasklist/Cockpit: a document bound to a
    // task (TASK_ID_ set) is a task attachment; one bound only to the instance is a process attachment.
    static final String TYPE_TASK_ATTACHMENT = "luke-task-attachment";
    static final String TYPE_PROCESS_ATTACHMENT = "luke-process-attachment";

    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public DocumentProcessLink(RuntimeService runtimeService, TaskService taskService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    /** Reference URL for a document's bytes — the proxy's authZ'd streaming endpoint (no S3 detail). */
    static String contentRef(String docId) {
        return "/api/documents/" + docId + "/content";
    }

    /** Mirror with no explicit task placement (task-scoped docs hang on their own task). */
    public void attach(Document doc) {
        attach(doc, null);
    }

    /**
     * Link {@code doc} into its Camunda instance as references only. Best-effort: Camunda errors (e.g. a
     * completed/unknown instance) are swallowed — the document row is already the source of truth.
     *
     * <p>{@code placementTaskId} is where the attachment is HUNG in Camunda for visibility — Camunda's
     * REST {@code /task/{id}/attachment} (e.g. the Tasklist UI) only returns task-scoped attachments, so a
     * process-level document (no taskId of its own) is placed on the active task to surface there. The
     * CLASSIFICATION (attachment type) still reflects the document's own taskId: a doc with no taskId is a
     * {@code luke-process-attachment} even when hung on a task for display.
     */
    public void attach(Document doc, String placementTaskId) {
        if (doc == null || !StringUtils.hasText(doc.getProcessInstanceId())) {
            return;   // not bound to a running instance (Flow-A before start) — nothing to mirror yet
        }
        String docId = doc.getId();
        String url = contentRef(docId);
        try {
            // Process variable carrying the reference (keyed by docId so multiple docs don't clobber).
            runtimeService.setVariable(doc.getProcessInstanceId(), "document:" + docId, url);
        } catch (RuntimeException e) {
            log.debug("setVariable for document {} on instance {} failed (ignored): {}",
                    docId, doc.getProcessInstanceId(), e.toString());
        }
        boolean taskScoped = StringUtils.hasText(doc.getTaskId());
        // Where to hang it: the doc's own task if it has one, else the caller-supplied active task (so it
        // shows in a task-centric UI). Null → process-level only (visible via process APIs, not /task/..).
        String camundaTaskId = taskScoped ? doc.getTaskId() : placementTaskId;
        try {
            // URL-mode attachment ONLY — never the InputStream overload (that would write a blob). The
            // attachment type carries the TASK/PROCESS classification; description keeps the doc kind.
            taskService.createAttachment(
                    taskScoped ? TYPE_TASK_ATTACHMENT : TYPE_PROCESS_ATTACHMENT,
                    camundaTaskId,
                    doc.getProcessInstanceId(),
                    doc.getFilename(),
                    doc.getKind(),
                    url);
        } catch (RuntimeException e) {
            log.debug("createAttachment for document {} failed (ignored): {}", docId, e.toString());
        }
    }
}
