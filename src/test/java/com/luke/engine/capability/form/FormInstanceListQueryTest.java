package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * #26: the form-instance list filters/searches/sorts server-side. Search resolves the
 * friendly form name (which lives on the definition, not the instance); submittedOnly
 * narrows to received submissions; sort is whitelisted; the page reports the total.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:listquerytest;DB_CLOSE_DELAY=-1",
        "luke.auth.gateway.enabled=false"
})
@Transactional
class FormInstanceListQueryTest {

    private static final String T = "lq-tenant";

    @Autowired private FormInstanceRepository instances;
    @Autowired private FormDefinitionRepository forms;

    private FormInstanceController controller;

    @BeforeEach
    void seed() {
        controller = new FormInstanceController(
                instances, forms, mock(FormVersionRepository.class), mock(FormSubmissionService.class));

        form("INTAKE", "Customer Intake");
        form("LEAVE", "Leave Request");
        inst("INTAKE", FormInstanceStates.SUBMITTED);
        inst("INTAKE", FormInstanceStates.SUBMITTED);
        inst("INTAKE", FormInstanceStates.CREATED);
        inst("LEAVE", FormInstanceStates.PROCESSED);
        inst("LEAVE", FormInstanceStates.SENT);
    }

    private void form(String code, String name) {
        FormDefinition f = new FormDefinition();
        f.setTenantId(T);
        f.setCode(code);
        f.setName(name);
        forms.save(f);
    }

    private void inst(String code, String state) {
        FormInstance i = new FormInstance();
        i.setTenantId(T);
        i.setToken(UUID.randomUUID().toString());
        i.setDefinitionCode(code);
        i.setVersion(1);
        i.setState(state);
        instances.save(i);
    }

    @Test
    void searchMatchesByFriendlyFormName() {
        var page = controller.list(T, null, null, false, "leave", null, null, 0, 50);
        assertEquals(2, page.total());
        assertTrue(page.items().stream().allMatch(i -> "LEAVE".equals(i.getDefinitionCode())));
    }

    @Test
    void submittedOnlyNarrowsToReceivedSubmissions() {
        var page = controller.list(T, null, null, true, null, null, null, 0, 50);
        // 2 SUBMITTED (INTAKE) + 1 PROCESSED (LEAVE)
        assertEquals(3, page.total());
        assertTrue(page.items().stream()
                .allMatch(i -> FormInstanceStates.SUBMITTED_STATES.contains(i.getState())));
    }

    @Test
    void stateFilterIsExact() {
        var page = controller.list(T, FormInstanceStates.CREATED, null, false, null, null, null, 0, 50);
        assertEquals(1, page.total());
        assertEquals(FormInstanceStates.CREATED, page.items().get(0).getState());
    }

    @Test
    void whitelistedSortAscendingByDefinitionCode() {
        var page = controller.list(T, null, null, false, null, "definitionCode", "asc", 0, 50);
        assertEquals("INTAKE", page.items().get(0).getDefinitionCode());
        assertEquals("LEAVE", page.items().get(page.items().size() - 1).getDefinitionCode());
    }

    @Test
    void pageReportsTotalIndependentOfPageSize() {
        var page = controller.list(T, null, null, false, null, null, null, 0, 2);
        assertEquals(2, page.items().size());
        assertEquals(5, page.total());
    }
}
