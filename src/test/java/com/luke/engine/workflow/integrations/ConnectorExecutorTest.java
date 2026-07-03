package com.luke.engine.workflow.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.workflow.WorkflowVersion;
import com.luke.engine.workflow.WorkflowVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConnectorExecutor} (WF-11) — the outbound rail. {@link DelegateExecution}
 * and the repos/Nango client are mocked; proves binding resolution, the happy path (invoke +
 * output + meter), and the error classification (reconnect / retry / route).
 */
class ConnectorExecutorTest {

    private static final String TENANT = "t1";
    private static final String NODE_ID = "n4";
    private static final String PROC_DEF_ID = "wf_onboard_v3:1:deploy1";
    private static final String PROCESS_ID = "wf_onboard_v3";

    private static final String VERSION_JSON =
            """
            { "id": "wf_onboard", "version": 3,
              "trigger": { "capability": "forms", "type": "form.submitted" },
              "nodes": [
                { "id": "n4", "kind": "action", "capability": "integrations", "provider": "salesforce",
                  "action": "upsertOpportunity", "connection": "conn1",
                  "input": { "name": "acme" }, "output": "sfOpportunity", "next": "end" }
              ] }
            """;

    private final WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    private final IntegrationConnectionRepository connections = mock(IntegrationConnectionRepository.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final NangoClient nango = mock(NangoClient.class);
    private final UsageEmitter usage = mock(UsageEmitter.class);
    private final DelegateExecution execution = mock(DelegateExecution.class);

    private final ConnectorExecutor executor =
            new ConnectorExecutor(versions, connections, connectionService, nango, usage, new ObjectMapper(), List.of());

    @BeforeEach
    void setUp() {
        when(execution.getCurrentActivityId()).thenReturn(NODE_ID);
        when(execution.getTenantId()).thenReturn(TENANT);
        when(execution.getProcessDefinitionId()).thenReturn(PROC_DEF_ID);
        when(execution.getProcessInstanceId()).thenReturn("pi1");

        WorkflowVersion v = new WorkflowVersion("def1", 3, VERSION_JSON, "u");
        when(versions.findFirstByProcessId(PROCESS_ID)).thenReturn(Optional.of(v));
    }

    private IntegrationConnection activeConn() {
        IntegrationConnection c = new IntegrationConnection("conn1", TENANT, "salesforce", "u");
        c.setStatus(IntegrationConnectionStatus.ACTIVE);
        c.setNangoConnectionId("nango_1");
        return c;
    }

    @Test
    void happyPathInvokesActionStoresOutputAndMeters() {
        when(connections.findByIdAndTenantId("conn1", TENANT)).thenReturn(Optional.of(activeConn()));
        Map<String, Object> result = Map.of("id", "006xx");
        when(nango.triggerAction(eq("salesforce"), eq("nango_1"), eq("upsertOpportunity"), any()))
                .thenReturn(result);

        executor.execute(execution);

        verify(execution).setVariable("sfOpportunity", result);
        verify(usage).emitExecution(TENANT, "conn1", "pi1:n4");
    }

    @Test
    void authFailureMarksReconnectAndRaisesBpmnError() {
        when(connections.findByIdAndTenantId("conn1", TENANT)).thenReturn(Optional.of(activeConn()));
        when(nango.triggerAction(anyString(), anyString(), anyString(), any()))
                .thenThrow(new NangoAuthException("token expired"));

        assertThatThrownBy(() -> executor.execute(execution))
                .isInstanceOf(BpmnError.class)
                .satisfies(e -> assertThat(((BpmnError) e).getErrorCode()).isEqualTo("connection-error"));

        verify(connectionService).markNeedsReconnect(eq("conn1"), anyString());
        verify(usage, never()).emitExecution(anyString(), anyString(), anyString());
    }

    @Test
    void businessFailureRoutesToFallbackViaBpmnError() {
        when(connections.findByIdAndTenantId("conn1", TENANT)).thenReturn(Optional.of(activeConn()));
        when(nango.triggerAction(anyString(), anyString(), anyString(), any()))
                .thenThrow(new NangoActionException("required field missing"));

        assertThatThrownBy(() -> executor.execute(execution))
                .isInstanceOf(BpmnError.class)
                .satisfies(e -> assertThat(((BpmnError) e).getErrorCode()).isEqualTo("action-error"));
    }

    @Test
    void transientFailurePropagatesSoCamundaRetries() {
        when(connections.findByIdAndTenantId("conn1", TENANT)).thenReturn(Optional.of(activeConn()));
        when(nango.triggerAction(anyString(), anyString(), anyString(), any()))
                .thenThrow(new NangoServerException("502 bad gateway"));

        assertThatThrownBy(() -> executor.execute(execution)).isInstanceOf(NangoServerException.class);
    }

    @Test
    void dispatchesNonIntegrationActionToItsCapabilityHandler() {
        String emailJson =
                """
                { "id": "wf", "version": 3,
                  "trigger": { "capability": "forms", "type": "form.submitted" },
                  "nodes": [ { "id": "n9", "kind": "action", "capability": "email", "action": "send",
                               "input": { "to": "x@y.com" }, "output": "res", "next": "end" } ] }
                """;
        when(execution.getCurrentActivityId()).thenReturn("n9");
        when(versions.findFirstByProcessId(PROCESS_ID))
                .thenReturn(Optional.of(new WorkflowVersion("def1", 3, emailJson, "u")));

        final boolean[] called = { false };
        com.luke.engine.workflow.CapabilityActionHandler handler =
                new com.luke.engine.workflow.CapabilityActionHandler() {
                    @Override public String capability() { return "email"; }
                    @Override public Object execute(String t, com.luke.engine.workflow.WorkflowNode n, Map<String, Object> v) {
                        called[0] = true;
                        return Map.of("emailId", "em1");
                    }
                };
        ConnectorExecutor exec = new ConnectorExecutor(
                versions, connections, connectionService, nango, usage, new ObjectMapper(), List.of(handler));

        exec.execute(execution);

        assertThat(called[0]).isTrue();
        verify(execution).setVariable("res", Map.of("emailId", "em1"));
        verify(nango, never()).triggerAction(anyString(), anyString(), anyString(), any());
    }

    @Test
    void inactiveConnectionRaisesBpmnErrorAndSkipsNango() {
        IntegrationConnection pending = new IntegrationConnection("conn1", TENANT, "salesforce", "u");
        pending.setStatus(IntegrationConnectionStatus.NEEDS_RECONNECT);
        when(connections.findByIdAndTenantId("conn1", TENANT)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> executor.execute(execution))
                .isInstanceOf(BpmnError.class)
                .satisfies(e -> assertThat(((BpmnError) e).getErrorCode()).isEqualTo("connection-error"));

        verify(nango, never()).triggerAction(anyString(), anyString(), anyString(), any());
    }
}
