package com.luke.engine.form;

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

/** #23: the inbox query is paginated and the page size is capped (no unbounded .list()). */
class FormInboxPaginationTest {

    @Test
    void requestedPageSizeIsCappedAndListPageIsUsed() {
        TaskService taskService = mock(TaskService.class);
        RuntimeService runtimeService = mock(RuntimeService.class);
        TaskQuery query = mock(TaskQuery.class, RETURNS_SELF); // fluent builder returns itself
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.listPage(anyInt(), anyInt())).thenReturn(List.<Task>of());

        new FormInboxController(taskService, runtimeService).list("tenant-a", 0, 5000);

        // Over-large maxResults is clamped to MAX_PAGE (200); listPage (not list) is used.
        verify(query).listPage(0, 200);
    }

    @Test
    void negativeOffsetIsClampedToZero() {
        TaskService taskService = mock(TaskService.class);
        RuntimeService runtimeService = mock(RuntimeService.class);
        TaskQuery query = mock(TaskQuery.class, RETURNS_SELF);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.listPage(anyInt(), anyInt())).thenReturn(List.<Task>of());

        new FormInboxController(taskService, runtimeService).list("tenant-a", -5, 10);

        verify(query).listPage(0, 10);
    }
}
