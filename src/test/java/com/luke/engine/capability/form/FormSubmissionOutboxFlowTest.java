package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.luke.engine.form.FormProcessDeployer;
import java.util.Map;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end proof of the durable submit→process→write-back chain (the M3 net-new
 * behavior the review flagged as silently-broken if mis-wired):
 *   1. submit() atomically marks the instance SUBMITTED AND writes a QUEUED outbox row,
 *   2. the consumer starts the Camunda process IN-PROCESS and flips the row to PUBLISHED,
 *   3. completing the review task runs the write-back delegate → instance PROCESSED.
 * The scheduler is disabled so the test drives the consumer deterministically.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:outboxflow;DB_CLOSE_DELAY=-1",
        "luke.forms.outbox-enabled=false"
})
class FormSubmissionOutboxFlowTest {

    @Autowired FormSubmissionService submissions;
    @Autowired FormInstanceRepository instances;
    @Autowired FormSubmissionOutboxRepository outbox;
    @Autowired FormSubmissionOutboxConsumer consumer;
    @Autowired FormProcessDeployer deployer;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;

    @Test
    void durableSubmit_startsProcess_andWritesBack() {
        String tenant = "t-outbox";
        deployer.deployFor(tenant);

        FormInstance inst = new FormInstance();
        inst.setTenantId(tenant);
        inst.setToken("tok-" + System.nanoTime());
        inst.setDefinitionCode("DEMO");
        inst.setVersion(1);
        inst.setState(FormInstanceStates.IN_PROGRESS);
        inst.setData(Map.of("seed", true));
        instances.save(inst);

        // 1) atomic submit → SUBMITTED + a QUEUED outbox row (same tx)
        submissions.submit(inst, Map.of("answer", "42"));
        assertEquals(FormInstanceStates.SUBMITTED, instances.findById(inst.getId()).orElseThrow().getState());
        FormSubmissionOutbox row = outbox.findByBusinessKey(inst.getId()).orElseThrow();
        assertEquals("QUEUED", row.getStatus());

        // 2) consumer starts the process in-process and publishes the row
        consumer.process(row);
        FormSubmissionOutbox published = outbox.findByBusinessKey(inst.getId()).orElseThrow();
        assertEquals("PUBLISHED", published.getStatus());
        assertNotNull(published.getProcessInstanceId());
        assertEquals(1, runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(inst.getId()).count());
        assertEquals("STARTED", instances.findById(inst.getId()).orElseThrow()
                .getContext().get("processStartStatus"));

        // 3) complete the review task → write-back delegate marks the instance PROCESSED
        Task task = taskService.createTaskQuery()
                .processInstanceId(published.getProcessInstanceId()).singleResult();
        assertNotNull(task);
        taskService.complete(task.getId());
        assertEquals(FormInstanceStates.PROCESSED,
                instances.findById(inst.getId()).orElseThrow().getState());
    }
}
