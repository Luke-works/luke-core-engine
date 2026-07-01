package com.luke.engine.workflow;

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
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the WORKFLOW design-time lifecycle (WF-4), with the repositories and
 * the Camunda deployer mocked but the real compiler + registry in play. Proves the
 * publish gates (sign-off, compile, capability resolution) and the check-in snapshot
 * behaviour.
 */
class WorkflowDefinitionServiceTest {

    private static final String TENANT = "t1";
    private static final String DEF_ID = "d1";
    private static final String VALID_JSON =
            """
            { "id": "wf", "version": 1,
              "trigger": { "capability": "forms", "type": "form.submitted" },
              "nodes": [ { "id": "n1", "kind": "action", "capability": "email", "action": "send", "next": "end" } ] }
            """;

    private final WorkflowDefinitionRepository defs = mock(WorkflowDefinitionRepository.class);
    private final WorkflowVersionRepository vers = mock(WorkflowVersionRepository.class);
    private final WorkflowProcessDeployer deployer = mock(WorkflowProcessDeployer.class);
    private final WorkflowDefinitionService service = new WorkflowDefinitionService(
            defs, vers, new WorkflowCompiler(), deployer, new ObjectMapper(), new StepTypeRegistry());

    private WorkflowDefinition def(String draftJson) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setId(DEF_ID);
        d.setTenantId(TENANT);
        d.setName("wf");
        d.setDraftJson(draftJson);
        return d;
    }

    private WorkflowVersion compiledVersion(boolean signedOff) {
        WorkflowVersion v = new WorkflowVersion(DEF_ID, 1, VALID_JSON, "u");
        v.setCompileOk(true);
        v.setBpmnXml("<xml/>");
        v.setProcessId("wf_v1");
        if (signedOff) {
            v.setSignedOffAt(LocalDateTime.now());
            v.setSignedOffBy("u");
        }
        return v;
    }

    @Test
    void checkInSnapshotsAndCompilesTheDraft() {
        when(defs.findByIdAndTenantId(DEF_ID, TENANT)).thenReturn(Optional.of(def(VALID_JSON)));
        when(vers.save(any())).thenAnswer(a -> a.getArgument(0));
        when(defs.save(any())).thenAnswer(a -> a.getArgument(0));

        WorkflowVersion v = service.checkIn(TENANT, DEF_ID, "u");

        assertThat(v.getVersion()).isEqualTo(1);
        assertThat(v.isCompileOk()).isTrue();
        assertThat(v.getBpmnXml()).isNotNull();
        assertThat(v.getProcessId()).isEqualTo("wf_v1");
    }

    @Test
    void checkInRecordsCompileErrorButStillSnapshots() {
        when(defs.findByIdAndTenantId(DEF_ID, TENANT))
                .thenReturn(Optional.of(def("{ \"id\": \"wf\", \"version\": 1, \"nodes\": [] }"))); // no trigger
        when(vers.save(any())).thenAnswer(a -> a.getArgument(0));
        when(defs.save(any())).thenAnswer(a -> a.getArgument(0));

        WorkflowVersion v = service.checkIn(TENANT, DEF_ID, "u");

        assertThat(v.isCompileOk()).isFalse();
        assertThat(v.getCompileError()).isNotBlank();
    }

    @Test
    void signOffRejectsANonCompilingVersion() {
        WorkflowVersion broken = new WorkflowVersion(DEF_ID, 1, VALID_JSON, "u");
        broken.setCompileOk(false);
        when(defs.findByIdAndTenantId(DEF_ID, TENANT)).thenReturn(Optional.of(def(VALID_JSON)));
        when(vers.findByDefinitionIdAndVersion(DEF_ID, 1)).thenReturn(Optional.of(broken));

        assertThatThrownBy(() -> service.signOff(TENANT, DEF_ID, 1, "u"))
                .isInstanceOf(WorkflowLifecycleException.class)
                .hasMessageContaining("does not compile");
    }

    @Test
    void publishRefusesAnUnsignedVersionAndDoesNotDeploy() {
        when(defs.findByIdAndTenantId(DEF_ID, TENANT)).thenReturn(Optional.of(def(VALID_JSON)));
        when(vers.findByDefinitionIdAndVersion(DEF_ID, 1)).thenReturn(Optional.of(compiledVersion(false)));

        assertThatThrownBy(() -> service.publish(TENANT, DEF_ID, 1, "u"))
                .isInstanceOf(WorkflowLifecycleException.class)
                .hasMessageContaining("not signed off");
        verify(deployer, never()).deploy(anyString(), anyString(), anyString());
    }

    @Test
    void publishDeploysASignedOffVersionAndMarksPublished() {
        WorkflowDefinition d = def(VALID_JSON);
        when(defs.findByIdAndTenantId(DEF_ID, TENANT)).thenReturn(Optional.of(d));
        when(vers.findByDefinitionIdAndVersion(DEF_ID, 1)).thenReturn(Optional.of(compiledVersion(true)));
        when(defs.save(any())).thenAnswer(a -> a.getArgument(0));
        when(deployer.deploy(anyString(), anyString(), anyString())).thenReturn("dep1");

        WorkflowDefinition out = service.publish(TENANT, DEF_ID, 1, "u");

        verify(deployer).deploy(eq(TENANT), eq("wf_v1"), eq("<xml/>"));
        assertThat(out.getPublishedVersion()).isEqualTo(1);
        assertThat(out.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void publishRejectsAnUnknownCapability() {
        String unknownCap =
                """
                { "id": "wf", "version": 1,
                  "trigger": { "capability": "forms", "type": "form.submitted" },
                  "nodes": [ { "id": "n1", "kind": "action", "capability": "quantum", "action": "entangle", "next": "end" } ] }
                """;
        WorkflowVersion v = compiledVersion(true);
        v.setJsonSource(unknownCap);
        when(defs.findByIdAndTenantId(DEF_ID, TENANT)).thenReturn(Optional.of(def(unknownCap)));
        when(vers.findByDefinitionIdAndVersion(DEF_ID, 1)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.publish(TENANT, DEF_ID, 1, "u"))
                .isInstanceOf(WorkflowLifecycleException.class)
                .hasMessageContaining("quantum");
        verify(deployer, never()).deploy(anyString(), anyString(), anyString());
    }
}
