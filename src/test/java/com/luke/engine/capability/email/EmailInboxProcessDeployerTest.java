package com.luke.engine.capability.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.TaskService;
import org.finos.fluxnova.bpm.engine.runtime.ProcessInstance;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The default per-tenant email-inbox process deploys tenant-scoped, is idempotent, and starting
 * it creates the "Review inbound email" task that surfaces the message as work (mirrors the
 * forms/signature/phone default processes).
 */
@SpringBootTest
class EmailInboxProcessDeployerTest {

    private static final String T = "EMAIL-INBOX-TEST";

    @Autowired private EmailInboxProcessDeployer deployer;
    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private IdentityService identityService;

    @AfterEach
    void cleanup() {
        for (var d : repositoryService.createDeploymentQuery().tenantIdIn(T).list()) {
            repositoryService.deleteDeployment(d.getId(), true); // cascade instances + tasks
        }
    }

    @Test
    void deploysTenantScopedIdempotentlyAndStartsAReviewTask() {
        deployer.deployFor(T);
        deployer.deployFor(T); // idempotent — duplicate filtering, still one definition

        long defs = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("EmailInboxProcess").tenantIdIn(T).count();
        assertThat(defs).isEqualTo(1);

        identityService.setAuthentication(null, null, List.of(T));
        try {
            ProcessInstance pi = runtimeService.createProcessInstanceByKey("EmailInboxProcess")
                    .processDefinitionTenantId(T)
                    .setVariable("emailFrom", "sender@example.com")
                    .setVariable("emailSubject", "Hello")
                    .execute();

            Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
            assertThat(task).isNotNull();
            assertThat(task.getName()).isEqualTo("Review inbound email");
        } finally {
            identityService.clearAuthentication();
        }
    }
}
