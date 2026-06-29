package com.luke.engine.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.access.CapabilityAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * The FORMS capability carve-out: FORMS submission docs pass on the capability gate alone (no DOC-4
 * candidate-group context), while other capabilities still require task/process context. The context
 * resolver here DENIES everything, so any allow must come from the FORMS carve-out.
 */
@ExtendWith(MockitoExtension.class)
class DocumentAccessGuardTest {

    @Mock CapabilityAccessService capabilities;

    /** Deny-all context resolver — isolates the guard's capability/carve-out logic. */
    private static final TaskAccessResolver DENY_ALL = new TaskAccessResolver() {
        @Override public boolean canAccess(String t, String u, Document d) { return false; }
        @Override public boolean canUpload(String t, String u, String pr, String pi, String ti) { return false; }
    };

    private DocumentAccessGuard guard() {
        return new DocumentAccessGuard(capabilities, DENY_ALL);
    }

    private static Document doc(String capability) {
        Document d = new Document();
        d.setTenantId("t1");
        d.setCapability(capability);
        d.setProcessInstanceId("P1");
        d.setTaskId("task-9");
        return d;
    }

    @Test
    void formsDocReadableOnCapabilityAloneDespiteNoContext() {
        when(capabilities.isAllowed("t1", "u1", "FORMS", false)).thenReturn(true);
        assertThat(guard().mayRead("t1", "u1", doc("FORMS"))).isTrue();   // carve-out: no candidate access needed
    }

    @Test
    void nonFormsDocStillRequiresContext() {
        when(capabilities.isAllowed("t1", "u1", "SIGNATURES", false)).thenReturn(true);
        assertThat(guard().mayRead("t1", "u1", doc("SIGNATURES"))).isFalse(); // DENY_ALL context blocks it
    }

    @Test
    void formsTaskUploadNeedsCapabilityWriteOnly() {
        when(capabilities.isAllowed("t1", "u1", "FORMS", true)).thenReturn(true);
        // taskId set but no task access (DENY_ALL) — still allowed for FORMS.
        guard().requireUpload("t1", "u1", "FORMS", "proc-A", "P1", "task-9");
    }

    @Test
    void nonFormsTaskUploadStillNeedsTaskAccess() {
        when(capabilities.isAllowed("t1", "u1", "SIGNATURES", true)).thenReturn(true);
        assertThatThrownBy(() -> guard().requireUpload("t1", "u1", "SIGNATURES", "proc-A", "P1", "task-9"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
