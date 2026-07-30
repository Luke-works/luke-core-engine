package com.luke.engine.capability.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the two delegates the approval process runs.
 *
 * <p>The provisioning assertions are the important ones: it must grant BEFORE it records the
 * decision, and it must throw rather than swallow — a process that says "approved" while the
 * member holds nothing is a silent privilege gap, and the whole point of putting provisioning on
 * a service task is that a failure leaves a retryable incident instead of a lie.
 */
class AccessRequestDelegatesTest {

    private static final String TENANT = "TEN-ACM-01JAN26";
    private static final String MEMBER = "workos:user_member";
    private static final String OWNER = "workos:user_owner";
    private static final String CODE = "FORMS";
    private static final String REQ_ID = "req-1";
    private static final String PID = "proc-1";

    private AccessRequestRepository requests;
    private CapabilityGrantController grantController;
    private AccessProvisioningDelegate provisioning;
    private AccessRequestStateDelegate state;
    private AccessRequest req;

    @BeforeEach
    void setUp() {
        requests = mock(AccessRequestRepository.class);
        grantController = mock(CapabilityGrantController.class);
        provisioning = new AccessProvisioningDelegate(requests, grantController);
        state = new AccessRequestStateDelegate(requests);

        req = new AccessRequest(TENANT, MEMBER, CODE, CapabilityLevel.READ);
        req.setId(REQ_ID);
        when(requests.findById(REQ_ID)).thenReturn(Optional.of(req));
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private DelegateExecution execution() {
        DelegateExecution ex = mock(DelegateExecution.class);
        when(ex.getProcessInstanceId()).thenReturn(PID);
        when(ex.getVariable(AccessProcessVariables.ACCESS_REQUEST_ID)).thenReturn(REQ_ID);
        return ex;
    }

    /* ── provisioning ───────────────────────────────────────── */

    @Test
    void provisioningGrantsAtTheApprovedLevelAndMarksApproved() {
        DelegateExecution ex = execution();
        when(ex.getVariable(AccessProcessVariables.GRANT_LEVEL)).thenReturn(CapabilityLevel.CONTRIBUTOR);
        when(ex.getVariable(AccessProcessVariables.DECIDED_BY)).thenReturn(OWNER);

        provisioning.execute(ex);

        verify(grantController, times(1)).setGrant(eq(TENANT), eq(MEMBER), eq(CODE), eq(OWNER),
                eq(new CapabilityGrantController.GrantBody(CapabilityLevel.CONTRIBUTOR)));
        assertThat(req.getStatus()).isEqualTo(AccessRequest.APPROVED);
        assertThat(req.getRequestedLevel()).isEqualTo(CapabilityLevel.CONTRIBUTOR);
        assertThat(req.getDecidedBy()).isEqualTo(OWNER);
        assertThat(req.getDecidedAt()).isNotNull();
        assertThat(req.getProcessInstanceId()).isEqualTo(PID);
    }

    @Test
    void provisioningFallsBackToTheRequestedLevelWhenTheOwnerDidNotOverride() {
        DelegateExecution ex = execution();
        when(ex.getVariable(AccessProcessVariables.GRANT_LEVEL)).thenReturn(null);

        provisioning.execute(ex);

        verify(grantController).setGrant(any(), any(), any(), any(),
                eq(new CapabilityGrantController.GrantBody(CapabilityLevel.READ)));
    }

    @Test
    void provisioningRefusesAnInvalidLevelRatherThanGrantingSomethingUnknown() {
        DelegateExecution ex = execution();
        when(ex.getVariable(AccessProcessVariables.GRANT_LEVEL)).thenReturn("superuser");

        assertThatThrownBy(() -> provisioning.execute(ex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("superuser");

        verify(grantController, never()).setGrant(any(), any(), any(), any(), any());
        assertThat(req.getStatus()).isEqualTo(AccessRequest.PENDING);
    }

    /** If the grant throws, the request must NOT be left claiming approval. */
    @Test
    void provisioningDoesNotMarkApprovedWhenTheGrantFails() {
        DelegateExecution ex = execution();
        when(grantController.setGrant(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("subscription missing"));

        assertThatThrownBy(() -> provisioning.execute(ex)).isInstanceOf(RuntimeException.class);

        assertThat(req.getStatus()).isEqualTo(AccessRequest.PENDING);
        verify(requests, never()).save(any());
    }

    @Test
    void provisioningFailsLoudlyWhenTheRequestVanished() {
        DelegateExecution ex = execution();
        when(requests.findById(REQ_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioning.execute(ex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer exists");
    }

    /* ── state write-back ───────────────────────────────────── */

    @Test
    void returningRecordsTheReasonAndHandsItBack() {
        DelegateExecution ex = execution();
        when(ex.getVariableLocal(AccessProcessVariables.TARGET_STATE)).thenReturn(AccessRequest.RETURNED);
        when(ex.getVariable(AccessProcessVariables.DECISION_NOTE)).thenReturn("too broad");
        when(ex.getVariable(AccessProcessVariables.DECIDED_BY)).thenReturn(OWNER);

        state.execute(ex);

        // RETURNED, not DENIED: the process is still live and waiting on the requester.
        assertThat(req.getStatus()).isEqualTo(AccessRequest.RETURNED);
        assertThat(req.getDecisionNote()).isEqualTo("too broad");
        assertThat(req.getDecidedBy()).isEqualTo(OWNER);
    }

    @Test
    void reopeningCountsTheResubmitAndAdoptsTheRevisedLevel() {
        req.setStatus(AccessRequest.RETURNED);
        req.setDecidedBy(OWNER);
        DelegateExecution ex = execution();
        when(ex.getVariableLocal(AccessProcessVariables.TARGET_STATE)).thenReturn(AccessRequest.PENDING);
        when(ex.getVariable(AccessProcessVariables.REQUESTED_LEVEL)).thenReturn(CapabilityLevel.READ_WRITE);

        state.execute(ex);

        assertThat(req.getStatus()).isEqualTo(AccessRequest.PENDING);
        assertThat(req.getRequestedLevel()).isEqualTo(CapabilityLevel.READ_WRITE);
        assertThat(req.getResubmitCount()).isEqualTo(1);
        // The previous decision no longer stands.
        assertThat(req.getDecidedBy()).isNull();
        assertThat(req.getDecidedAt()).isNull();
    }

    @Test
    void reopeningIgnoresAnInvalidRevisedLevel() {
        DelegateExecution ex = execution();
        when(ex.getVariableLocal(AccessProcessVariables.TARGET_STATE)).thenReturn(AccessRequest.PENDING);
        when(ex.getVariable(AccessProcessVariables.REQUESTED_LEVEL)).thenReturn("superuser");

        state.execute(ex);

        assertThat(req.getRequestedLevel()).isEqualTo(CapabilityLevel.READ);
    }

    @Test
    void withdrawingClosesTheRequest() {
        DelegateExecution ex = execution();
        when(ex.getVariableLocal(AccessProcessVariables.TARGET_STATE)).thenReturn(AccessRequest.CANCELLED);

        state.execute(ex);

        assertThat(req.getStatus()).isEqualTo(AccessRequest.CANCELLED);
    }

    @Test
    void aMissingOrUnknownTargetStateIsFatal() {
        DelegateExecution ex = execution();
        when(ex.getVariableLocal(AccessProcessVariables.TARGET_STATE)).thenReturn(null);
        assertThatThrownBy(() -> state.execute(ex)).isInstanceOf(IllegalStateException.class);

        DelegateExecution ex2 = execution();
        when(ex2.getVariableLocal(AccessProcessVariables.TARGET_STATE)).thenReturn("NONSENSE");
        assertThatThrownBy(() -> state.execute(ex2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NONSENSE");
    }
}
