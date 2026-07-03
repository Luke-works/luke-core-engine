package com.luke.engine.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.task.TaskQuery;
import org.junit.jupiter.api.Test;

/** #23/#26: the inbox query is paginated + size-capped, reports a total, and supports
 *  server-side search + whitelisted sort. */
class FormInboxPaginationTest {

    private TaskQuery query;

    private FormInboxController controller() {
        TaskService taskService = mock(TaskService.class);
        RuntimeService runtimeService = mock(RuntimeService.class);
        query = mock(TaskQuery.class, RETURNS_SELF); // fluent builder returns itself
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.listPage(anyInt(), anyInt())).thenReturn(List.<Task>of());
        return new FormInboxController(taskService, runtimeService);
    }

    @Test
    void requestedPageSizeIsCappedAndListPageIsUsed() {
        controller().list("tenant-a", null, null, null, 0, 5000);
        // Over-large maxResults is clamped to MAX_PAGE (200); listPage (not list) is used.
        verify(query).listPage(0, 200);
    }

    @Test
    void negativeOffsetIsClampedToZero() {
        controller().list("tenant-a", null, null, null, -5, 10);
        verify(query).listPage(0, 10);
    }

    @Test
    void reportsServerSideTotal() {
        FormInboxController c = controller();
        when(query.count()).thenReturn(42L);
        assertEquals(42L, c.list("tenant-a", null, null, null, 0, 10).total());
    }

    @Test
    void searchMatchesNameOrAssignee() {
        FormInboxController c = controller();
        c.list("tenant-a", "review", null, null, 0, 10);
        verify(query).or();
        verify(query).taskNameLike("%review%");
        verify(query).taskAssigneeLike("%review%");
        verify(query).endOr();
    }

    @Test
    void sortIsWhitelistedWithDirection() {
        FormInboxController c = controller();
        c.list("tenant-a", null, "name", "asc", 0, 10);
        verify(query).orderByTaskName();
        verify(query).asc();
    }
}
