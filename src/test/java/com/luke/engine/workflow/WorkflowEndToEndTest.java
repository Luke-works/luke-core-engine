package com.luke.engine.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.email.EmailMessage;
import com.luke.engine.capability.email.EmailService;
import com.luke.engine.workflow.integrations.IntegrationConnection;
import com.luke.engine.workflow.integrations.IntegrationConnectionRepository;
import com.luke.engine.workflow.integrations.IntegrationConnectionStatus;
import com.luke.engine.workflow.integrations.IntegrationUsageEventRepository;
import com.luke.engine.workflow.integrations.NangoClient;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * WF-13 — the whole spine on a LIVE CIBSeven engine: author JSON → compile → deploy →
 * start → run the golden scenario (form → email → human review → branch → Salesforce upsert)
 * to completion. This is the integration proof behind the unit tests: the compiled BPMN really
 * deploys and executes, both the user-task wait and the connector-action delegate really fire.
 *
 * <p>The job executor is disabled so async steps are driven deterministically; Nango is mocked
 * so the integration action returns a canned result without HTTP.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:wfe2e;DB_CLOSE_DELAY=-1",
        "luke.workflow.outbox-enabled=false",
        "camunda.bpm.job-execution.enabled=false"
})
class WorkflowEndToEndTest {

    private static final String TENANT = "t-e2e";
    private static final String GOLDEN_JSON =
            """
            {
              "id": "wf_e2e", "version": 1, "name": "Onboarding",
              "trigger": { "capability": "forms", "type": "form.submitted", "config": { "formId": "frm" } },
              "nodes": [
                { "id": "n1", "kind": "action", "capability": "email", "action": "send",
                  "input": { "to": "ops@lukeflow.com", "subject": "Intake received", "htmlBody": "<p>Thanks</p>" }, "next": "n2" },
                { "id": "n2", "kind": "task", "capability": "forms", "task": "review", "assignee": "queue:ops", "next": "n3" },
                { "id": "n3", "kind": "branch", "conditions": [ { "expr": "amount > 10000", "next": "n4" } ], "else": "end" },
                { "id": "n4", "kind": "action", "capability": "integrations", "provider": "salesforce",
                  "action": "upsertOpportunity", "connection": "conn_sf_primary", "output": "sfOpportunity",
                  "onError": { "fallback": "n5" }, "next": "end" },
                { "id": "n5", "kind": "action", "capability": "email", "action": "send",
                  "input": { "to": "ops@lukeflow.com", "subject": "SF sync failed" }, "next": "end" }
              ]
            }
            """;

    @Autowired WorkflowDefinitionService service;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired IntegrationUsageEventRepository usageEvents;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;
    @Autowired ManagementService managementService;

    @MockBean NangoClient nango;
    @MockBean EmailService emails;

    @Test
    void authorsCompilesDeploysAndRunsTheGoldenWorkflow() {
        // A tenant with an ACTIVE Salesforce connection.
        IntegrationConnection conn = new IntegrationConnection("conn_sf_primary", TENANT, "salesforce", "u");
        conn.setStatus(IntegrationConnectionStatus.ACTIVE);
        conn.setNangoConnectionId("nango_x");
        connections.save(conn);

        when(nango.triggerAction(eq("salesforce"), eq("nango_x"), eq("upsertOpportunity"), any()))
                .thenReturn(Map.of("id", "006ABC"));
        EmailMessage sent = org.mockito.Mockito.mock(EmailMessage.class);
        when(sent.getId()).thenReturn("em1");
        when(emails.sendRaw(anyString(), anyString(), any())).thenReturn(sent);

        // Author → check-in (compile) → sign-off → publish (deploy to the engine).
        WorkflowDefinition def = service.create(TENANT, "Onboarding", null, GOLDEN_JSON, "u");
        WorkflowVersion v = service.checkIn(TENANT, def.getId(), "u");
        assertThat(v.isCompileOk()).as("golden compiles").isTrue();
        service.signOff(TENANT, def.getId(), v.getVersion(), "u");
        service.publish(TENANT, def.getId(), v.getVersion(), "u");

        // Start the deployed process for this tenant, high-value so the branch takes the SF path.
        ProcessInstance pi = runtimeService.createProcessInstanceByKey(v.getProcessId())
                .processDefinitionTenantId(TENANT)
                .businessKey("bk-e2e")
                .setVariable("amount", 20000)
                .execute();
        assertThat(pi).isNotNull();

        drainJobs(); // run the async email action → park at the human review task

        Task review = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(review).as("parked at the review user task").isNotNull();
        assertThat(review.getTaskDefinitionKey()).isEqualTo("n2");

        taskService.complete(review.getId()); // approve → branch → SF action (async)
        drainJobs();                          // run the connector-action delegate → Nango → end

        // Both outbound handlers fired: the email action sent, the integration action hit (mocked) Nango + metered.
        verify(emails, atLeastOnce()).sendRaw(anyString(), anyString(), any());
        verify(nango).triggerAction(eq("salesforce"), eq("nango_x"), eq("upsertOpportunity"), any());
        assertThat(usageEvents.count()).isGreaterThanOrEqualTo(1);

        // The process ran to completion — no active instance remains.
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
    }

    /** Execute all waiting jobs until the process settles (job executor is disabled in this test). */
    private void drainJobs() {
        for (int i = 0; i < 25; i++) {
            List<Job> jobs = managementService.createJobQuery().list();
            if (jobs.isEmpty()) return;
            for (Job job : jobs) {
                managementService.executeJob(job.getId());
            }
        }
    }
}
