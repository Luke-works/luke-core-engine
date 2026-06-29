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
    private static final String ATTACHMENT_TYPE = "luke-document";

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

    /**
     * Link {@code doc} into its Camunda instance as references only. Best-effort: Camunda errors (e.g. a
     * completed/unknown instance) are swallowed — the document row is already the source of truth.
     */
    public void attach(Document doc) {
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
        try {
            // URL-mode attachment ONLY — never the InputStream overload (that would write a blob).
            taskService.createAttachment(ATTACHMENT_TYPE,
                    StringUtils.hasText(doc.getTaskId()) ? doc.getTaskId() : null,
                    doc.getProcessInstanceId(),
                    doc.getFilename(),
                    doc.getKind(),
                    url);
        } catch (RuntimeException e) {
            log.debug("createAttachment for document {} failed (ignored): {}", docId, e.toString());
        }
    }
}
