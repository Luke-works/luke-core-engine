package com.luke.engine.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * DOC-9 verification: the Camunda mirror writes only REFERENCES — a process variable + a URL-mode
 * attachment — and NEVER the InputStream overload (which would persist a blob in ACT_GE_BYTEARRAY).
 */
class DocumentProcessLinkTest {

    private Document doc(String id, String pid, String taskId) {
        Document d = new Document();
        d.setId(id);
        d.setTenantId("t1");
        d.setProcessRef("proc-A");
        d.setProcessInstanceId(pid);
        d.setTaskId(taskId);
        d.setKind(Document.KIND_FORM_ATTACHMENT);
        d.setCapability("FORMS");
        d.setFilename("w9.pdf");
        return d;
    }

    @Test
    void attachesTaskScopedUrlReferenceNeverBytes() {
        RuntimeService runtime = Mockito.mock(RuntimeService.class);
        TaskService tasks = Mockito.mock(TaskService.class);
        DocumentProcessLink link = new DocumentProcessLink(runtime, tasks);

        Document d = doc("doc_1", "PID-1", "task-9");
        link.attach(d);

        String url = "/api/documents/doc_1/content";
        // process variable carries the reference, not bytes
        verify(runtime).setVariable("PID-1", "document:doc_1", url);
        // URL-mode attachment against the task (TASK_ID_ + PROC_INST_ID_)
        verify(tasks).createAttachment("luke-document", "task-9", "PID-1", "w9.pdf", "FORM_ATTACHMENT", url);
        // the InputStream overload (blob) is NEVER used
        verify(tasks, never()).createAttachment(any(), any(), any(), any(), any(), any(InputStream.class));
    }

    @Test
    void caseLevelAttachesWithNullTask() {
        RuntimeService runtime = Mockito.mock(RuntimeService.class);
        TaskService tasks = Mockito.mock(TaskService.class);
        DocumentProcessLink link = new DocumentProcessLink(runtime, tasks);

        link.attach(doc("doc_2", "PID-2", null));

        verify(tasks).createAttachment(eq("luke-document"), isNull(), eq("PID-2"),
                eq("w9.pdf"), eq("FORM_ATTACHMENT"), eq("/api/documents/doc_2/content"));
    }

    @Test
    void noInstanceNoMirror() {
        RuntimeService runtime = Mockito.mock(RuntimeService.class);
        TaskService tasks = Mockito.mock(TaskService.class);
        new DocumentProcessLink(runtime, tasks).attach(doc("doc_3", null, null));
        Mockito.verifyNoInteractions(runtime, tasks);
    }
}
