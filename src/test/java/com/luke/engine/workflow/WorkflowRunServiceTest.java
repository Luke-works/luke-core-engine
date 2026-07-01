package com.luke.engine.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/** Start-a-test-run resolves the published version's process key and starts it tenant-scoped;
 *  an unpublished workflow is a business error. */
class WorkflowRunServiceTest {

    private final WorkflowDefinitionService definitions = mock(WorkflowDefinitionService.class);
    private final WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class);
    private final HistoryService historyService = mock(HistoryService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final WorkflowRunService service =
            new WorkflowRunService(definitions, versions, runtimeService, historyService, taskService);

    @Test
    void startsTheProcessForThePublishedVersionScopedToTenant() {
        WorkflowDefinition def = mock(WorkflowDefinition.class);
        when(def.getPublishedVersion()).thenReturn(2);
        when(definitions.get("t1", "d1")).thenReturn(def);
        WorkflowVersion v = mock(WorkflowVersion.class);
        when(v.getProcessId()).thenReturn("wf_d1_v2");
        when(versions.findByDefinitionIdAndVersion("d1", 2)).thenReturn(Optional.of(v));

        ProcessInstantiationBuilder builder = mock(ProcessInstantiationBuilder.class, Answers.RETURNS_SELF);
        ProcessInstance pi = mock(ProcessInstance.class);
        when(pi.getId()).thenReturn("PI-1");
        when(runtimeService.createProcessInstanceByKey("wf_d1_v2")).thenReturn(builder);
        when(builder.execute()).thenReturn(pi);

        String id = service.startTestRun("t1", "d1", Map.of("amount", 5), "u1");

        assertThat(id).isEqualTo("PI-1");
        verify(builder).processDefinitionTenantId("t1");
    }

    @Test
    void unpublishedWorkflowIsABusinessError() {
        WorkflowDefinition def = mock(WorkflowDefinition.class);
        when(def.getPublishedVersion()).thenReturn(null);
        when(definitions.get("t1", "d1")).thenReturn(def);

        assertThatThrownBy(() -> service.startTestRun("t1", "d1", null, "u1"))
                .isInstanceOf(WorkflowLifecycleException.class)
                .hasMessageContaining("Publish");
    }
}
