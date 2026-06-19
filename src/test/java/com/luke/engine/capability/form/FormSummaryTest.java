package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #26: the cockpit summary rolls up per-definitionCode server-side, with epoch-ms "last". */
class FormSummaryTest {

    private FormInstanceController controller(FormInstanceRepository instances) {
        return new FormInstanceController(
                instances, mock(FormDefinitionRepository.class),
                mock(FormVersionRepository.class), mock(FormSubmissionService.class));
    }

    // Implement the projection directly — mocking a nested interface projection is
    // fiddly and a plain impl reads clearer.
    private FormInstanceRepository.DefinitionSummary row(String code, long total, long subs, LocalDateTime last) {
        return new FormInstanceRepository.DefinitionSummary() {
            public String getCode() { return code; }
            public long getTotal() { return total; }
            public long getSubs() { return subs; }
            public LocalDateTime getLastAt() { return last; }
        };
    }

    @Test
    void keysByDefinitionAndConvertsLastToEpochMillis() {
        FormInstanceRepository instances = mock(FormInstanceRepository.class);
        LocalDateTime last = LocalDateTime.of(2026, 6, 1, 12, 0);
        when(instances.summarizeByDefinition(eq("t"), any()))
                .thenReturn(List.of(row("INTAKE", 10, 4, last)));

        Map<String, FormInstanceController.DefinitionSummaryView> out = controller(instances).summary("t");

        FormInstanceController.DefinitionSummaryView v = out.get("INTAKE");
        assertEquals(10, v.total());
        assertEquals(4, v.subs());
        assertEquals(last.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), v.last());
        // counts the right states as submissions
        verify(instances).summarizeByDefinition("t", FormInstanceStates.SUBMITTED_STATES);
    }

    @Test
    void nullLastActivityStaysNull() {
        FormInstanceRepository instances = mock(FormInstanceRepository.class);
        when(instances.summarizeByDefinition(eq("t"), any()))
                .thenReturn(List.of(row("EMPTY", 0, 0, null)));

        assertNull(controller(instances).summary("t").get("EMPTY").last());
    }
}
