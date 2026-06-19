package com.luke.engine.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.Test;

/** #31: a process-start for a businessKey already running returns the existing
 *  instance instead of starting a duplicate. */
class InternalProcessServiceIdempotencyTest {

    @Test
    void doesNotStartASecondProcessForAnAlreadyRunningBusinessKey() {
        RuntimeService runtime = mock(RuntimeService.class);
        IdentityService identity = mock(IdentityService.class);
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class, RETURNS_SELF);
        ProcessInstance running = mock(ProcessInstance.class);
        when(running.getProcessInstanceId()).thenReturn("pid-existing");
        when(runtime.createProcessInstanceQuery()).thenReturn(query);
        when(query.list()).thenReturn(List.<ProcessInstance>of(running));

        InternalProcessService service = new InternalProcessService(runtime, identity);

        String pid = service.start("tenant-a", "bk-1", null);

        assertEquals("pid-existing", pid);
        verify(runtime, never()).createProcessInstanceByKey(anyString()); // no duplicate start
    }
}
