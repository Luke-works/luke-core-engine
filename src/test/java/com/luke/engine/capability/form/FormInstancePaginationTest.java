package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** #52: the form-instances list is paged + size-capped, and reports the total. */
class FormInstancePaginationTest {

    private FormInstanceController controller(FormInstanceRepository instances) {
        return new FormInstanceController(
                instances, mock(FormDefinitionRepository.class),
                mock(FormVersionRepository.class), mock(FormSubmissionService.class));
    }

    @Test
    void capsPageSizeAndReportsTotal() {
        FormInstanceRepository instances = mock(FormInstanceRepository.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        Page<FormInstance> page = new PageImpl<>(List.of(new FormInstance()), PageRequest.of(0, 200), 1234);
        when(instances.findByTenantId(eq("t"), pageable.capture())).thenReturn(page);

        FormInstanceController.PagedInstances result = controller(instances).list("t", null, null, 0, 5000);

        assertEquals(1234, result.total());           // total comes from the server, not a client count
        assertEquals(1, result.items().size());
        assertEquals(200, pageable.getValue().getPageSize()); // over-large maxResults capped to MAX_PAGE
    }

    @Test
    void derivesPageFromOffset() {
        FormInstanceRepository instances = mock(FormInstanceRepository.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(instances.findByTenantId(eq("t"), pageable.capture()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 200));

        controller(instances).list("t", null, null, 100, 50); // offset 100 / size 50 = page 2

        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(50, pageable.getValue().getPageSize());
    }
}
