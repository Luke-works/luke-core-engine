package com.luke.engine.capability.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import org.finos.fluxnova.bpm.model.bpmn.Bpmn;
import org.finos.fluxnova.bpm.model.bpmn.BpmnModelInstance;
import org.finos.fluxnova.bpm.model.bpmn.instance.SequenceFlow;
import org.finos.fluxnova.bpm.model.bpmn.instance.ServiceTask;
import org.finos.fluxnova.bpm.model.bpmn.instance.UserTask;
import org.junit.jupiter.api.Test;

/**
 * Structural tests for {@code AccessRequestApprovalProcess.bpmn}.
 *
 * <p>Reading the model validates the XML, and asserting on it pins the parts that are silently
 * fatal at runtime: a mistyped {@code ${approverGroup}} makes an approval task belong to nobody, a
 * wrong {@code targetRef} on the resubmit flow quietly drops the rework loop, and a delegate
 * expression that doesn't match a bean name only fails when a real request is decided. None of
 * that is visible in a unit test of the Java alone.
 */
class AccessRequestApprovalProcessTest {

    private static final String RESOURCE = "/AccessRequestApprovalProcess.bpmn";

    private BpmnModelInstance model() {
        InputStream in = getClass().getResourceAsStream(RESOURCE);
        assertThat(in).as("BPMN is on the classpath").isNotNull();
        return Bpmn.readModelFromStream(in);
    }

    private <T extends org.finos.fluxnova.bpm.model.bpmn.instance.FlowElement> T byId(
            BpmnModelInstance m, String id, Class<T> type) {
        return type.cast(m.getModelElementById(id));
    }

    @Test
    void theApprovalTaskRoutesToTheResolvedResourceOwnerGroup() {
        UserTask approve = byId(model(), AccessRequestController.TASK_APPROVE, UserTask.class);
        assertThat(approve).isNotNull();
        // Resolved per instance at start time — NOT a hardcoded group.
        assertThat(approve.getFluxnovaCandidateGroups())
                .isEqualTo("${" + AccessProcessVariables.APPROVER_GROUP + "}");
    }

    @Test
    void theRejectionTaskGoesBackToTheRequesterPersonally() {
        UserTask rework = byId(model(), AccessRequestController.TASK_REWORK, UserTask.class);
        assertThat(rework).isNotNull();
        assertThat(rework.getFluxnovaAssignee())
                .isEqualTo("${" + AccessProcessVariables.REQUESTER_ID + "}");
    }

    @Test
    void provisioningIsWiredToTheDelegateBean() {
        ServiceTask provision = byId(model(), "Activity_provision", ServiceTask.class);
        assertThat(provision.getFluxnovaDelegateExpression()).isEqualTo("${accessProvisioningDelegate}");
    }

    @Test
    void everyStateWriteBackUsesTheStateDelegate() {
        BpmnModelInstance m = model();
        for (String id : List.of("Activity_return", "Activity_reopen", "Activity_withdraw")) {
            ServiceTask t = byId(m, id, ServiceTask.class);
            assertThat(t.getFluxnovaDelegateExpression())
                    .as("%s delegate", id)
                    .isEqualTo("${accessRequestStateDelegate}");
        }
    }

    /**
     * The rework loop is the "rejections flow back to the user, naturally" requirement: a
     * resubmit must return to the SAME approval task, not to a dead end or a second start.
     */
    @Test
    void resubmittingLoopsBackToTheApprovalTask() {
        BpmnModelInstance m = model();
        SequenceFlow reopenToApproval = byId(m, "Flow_reopen_to_approval", SequenceFlow.class);
        assertThat(reopenToApproval.getSource().getId()).isEqualTo("Activity_reopen");
        assertThat(reopenToApproval.getTarget().getId()).isEqualTo(AccessRequestController.TASK_APPROVE);

        // ...and the approval task genuinely accepts both entries (first pass + every retry).
        UserTask approve = byId(m, AccessRequestController.TASK_APPROVE, UserTask.class);
        Collection<SequenceFlow> incoming = approve.getIncoming();
        assertThat(incoming).hasSize(2);
    }

    @Test
    void bothDecisionGatewaysAreDrivenByTheDocumentedVariables() {
        BpmnModelInstance m = model();
        assertThat(byId(m, "Flow_approved", SequenceFlow.class).getConditionExpression().getTextContent())
                .isEqualTo("${" + AccessProcessVariables.APPROVED + "}");
        assertThat(byId(m, "Flow_resubmit", SequenceFlow.class).getConditionExpression().getTextContent())
                .isEqualTo("${" + AccessProcessVariables.RESUBMIT + "}");
    }

    /**
     * The state delegate reads {@code targetState} as a LOCAL input variable; if the input mapping
     * were dropped it would throw at runtime on every rejection. Pin the three values.
     */
    @Test
    void eachStateTaskCarriesItsTargetStateInputMapping() {
        String xml = Bpmn.convertToString(model());
        for (String state : List.of(AccessRequest.RETURNED, AccessRequest.PENDING, AccessRequest.CANCELLED)) {
            assertThat(xml)
                    .as("targetState=%s input mapping", state)
                    .contains("<camunda:inputParameter name=\"" + AccessProcessVariables.TARGET_STATE
                            + "\">" + state + "</camunda:inputParameter>");
        }
    }
}
