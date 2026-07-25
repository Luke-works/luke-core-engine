package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;

/**
 * #46: the overdue-review escalation. The delegate emits the per-tenant overdue metric, and the
 * intake BPMN wires it behind a NON-INTERRUPTING boundary timer (the full @SpringBootTest suite
 * separately proves the BPMN deploys, since the form process is deployed during tenant setup).
 */
class OverdueReviewDelegateTest {

    private final MeterRegistry metrics = new SimpleMeterRegistry();
    private final OverdueReviewDelegate delegate = new OverdueReviewDelegate(metrics);

    @Test
    void incrementsTheOverdueCounterTaggedByTenant() {
        DelegateExecution ex = mock(DelegateExecution.class);
        when(ex.getVariable("formMetaData")).thenReturn("{\"tenantId\":\"T-1\",\"instanceId\":\"i-1\"}");
        when(ex.getProcessInstanceId()).thenReturn("proc-1");

        delegate.execute(ex);

        assertThat(metrics.counter("luke.forms.review.overdue", "tenant", "T-1").count()).isEqualTo(1.0);
    }

    @Test
    void tagsUnknownWhenNoTenantInMetadata() {
        DelegateExecution ex = mock(DelegateExecution.class);
        when(ex.getVariable("formMetaData")).thenReturn(null);

        delegate.execute(ex);

        assertThat(metrics.counter("luke.forms.review.overdue", "tenant", "unknown").count()).isEqualTo(1.0);
    }

    @Test
    void neverThrowsOnMalformedMetadata() {
        DelegateExecution ex = mock(DelegateExecution.class);
        when(ex.getVariable("formMetaData")).thenReturn("not-json{");

        assertThatCode(() -> delegate.execute(ex)).doesNotThrowAnyException();
    }

    @Test
    void intakeBpmnWiresANonInterruptingSlaEscalation() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/FormSubmissionIntakeProcess.bpmn"));
        assertThat(xml)
                .contains("cancelActivity=\"false\"")                     // non-interrupting → task not cancelled
                .contains("attachedToRef=\"Activity_1c3gauj\"")           // on the Review Submission task
                .contains("<bpmn:timeCycle>R/P3D</bpmn:timeCycle>")       // the SLA / recurrence
                .contains("${overdueReviewDelegate}");                    // escalation → overdue flag
    }
}
