package com.luke.engine.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.history.HistoricProcessInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/** Start-a-test-run resolves the published version's process key and starts it tenant-scoped;
 *  an unpublished workflow is a business error. */
class WorkflowRunServiceTest {

    private final WorkflowDefinitionService definitions = mock(WorkflowDefinitionService.class);
    private final WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class, Answers.RETURNS_DEEP_STUBS);
    private final HistoryService historyService = mock(HistoryService.class, Answers.RETURNS_DEEP_STUBS);
    private final TaskService taskService = mock(TaskService.class, Answers.RETURNS_DEEP_STUBS);
    private final ManagementService managementService = mock(ManagementService.class);
    private final WorkflowRunService service =
            new WorkflowRunService(definitions, versions, runtimeService, historyService, taskService, managementService);

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

    @Test
    void retryBumpsFailedJobRetriesAndSetsVariables() {
        HistoricProcessInstance h = mock(HistoricProcessInstance.class);
        when(h.getTenantId()).thenReturn("t1");
        when(h.getEndTime()).thenReturn(new java.util.Date()); // ended → runDetail stays shallow
        when(historyService.createHistoricProcessInstanceQuery().processInstanceId("PI-9").singleResult())
                .thenReturn(h);
        org.cibseven.bpm.engine.runtime.Incident inc = mock(org.cibseven.bpm.engine.runtime.Incident.class);
        when(inc.getIncidentType()).thenReturn("failedJob");
        when(inc.getConfiguration()).thenReturn("job-1");
        when(runtimeService.createIncidentQuery().processInstanceId("PI-9").list()).thenReturn(java.util.List.of(inc));

        service.retryRun("t1", "PI-9", Map.of("amount", 9));

        verify(runtimeService).setVariables("PI-9", Map.of("amount", 9));
        verify(managementService).setJobRetries("job-1", 3);
    }

    @Test
    void cancelDeletesTheInstance() {
        HistoricProcessInstance h = mock(HistoricProcessInstance.class);
        when(h.getTenantId()).thenReturn("t1");
        when(h.getEndTime()).thenReturn(new java.util.Date());
        when(historyService.createHistoricProcessInstanceQuery().processInstanceId("PI-9").singleResult())
                .thenReturn(h);

        service.cancelRun("t1", "PI-9");

        verify(runtimeService).deleteProcessInstance("PI-9", "Cancelled from the workflow builder");
    }

    @Test
    void retryOnAnotherTenantsRunIsRejected() {
        HistoricProcessInstance h = mock(HistoricProcessInstance.class);
        when(h.getTenantId()).thenReturn("other");
        when(historyService.createHistoricProcessInstanceQuery().processInstanceId("PI-9").singleResult())
                .thenReturn(h);

        assertThatThrownBy(() -> service.cancelRun("t1", "PI-9"))
                .isInstanceOf(WorkflowLifecycleException.class)
                .hasMessageContaining("not found");
    }
}
