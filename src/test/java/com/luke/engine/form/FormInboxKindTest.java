package com.luke.engine.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.luke.engine.capability.email.EmailInboxProcessDeployer;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The inbox reports what each task is ABOUT, so a caller can open it.
 *
 * <p>This closes a real bug rather than adding a nicety. The inbox query has always returned
 * every open user task for the tenant, so the "Review inbound email" tasks that EMAIL intake
 * creates already appeared in it — but every row claimed to be a form submission: {@code
 * instanceId} fell back to the business key, the UI passed that to the form-instance API, and
 * the row failed to open. These tests pin both branches of the discriminator.
 */
@SpringBootTest
class FormInboxKindTest {

    private static final String TENANT = "INBOX-KIND-TEST";

    @Autowired private FormInboxController inbox;
    @Autowired private EmailInboxProcessDeployer emailDeployer;
    @Autowired private FormProcessDeployer formDeployer;
    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private IdentityService identityService;

    @BeforeEach
    void deploy() {
        cleanup();
        emailDeployer.deployFor(TENANT);
        formDeployer.deployFor(TENANT);
    }

    @AfterEach
    void cleanup() {
        for (var d : repositoryService.createDeploymentQuery().tenantIdIn(TENANT).list()) {
            repositoryService.deleteDeployment(d.getId(), true);
        }
    }

    private void start(String key, String businessKey, Map<String, Object> vars) {
        identityService.setAuthentication(null, null, List.of(TENANT));
        try {
            runtimeService.createProcessInstanceByKey(key)
                    .processDefinitionTenantId(TENANT)
                    .businessKey(businessKey)
                    .setVariables(vars)
                    .execute();
        } finally {
            identityService.clearAuthentication();
        }
    }

    private Map<String, Object> rowNamed(String taskNamePrefix) {
        return inbox.list(TENANT, null, null, null, 0, 50).items().stream()
                .filter(m -> String.valueOf(m.get("name")).startsWith(taskNamePrefix))
                .findFirst().orElseThrow(() -> new AssertionError("no inbox row starting with " + taskNamePrefix));
    }

    @Test
    void anInboundEmailTaskIsReportedAsEmailAndCarriesItsMessage() {
        start("EmailInboxProcess", "email-inbox-msg-1", Map.of(
                "emailMessageId", "msg-1",
                "emailFrom", "jo@example.com",
                "emailBox", "support@acme.com",
                "emailTaskName", "Review: Refund request"));

        Map<String, Object> row = rowNamed("Review: Refund");

        assertThat(row.get("kind")).isEqualTo("email");
        assertThat(row.get("emailMessageId")).isEqualTo("msg-1");
        assertThat(row.get("emailFrom")).isEqualTo("jo@example.com");
        assertThat(row.get("emailBox")).isEqualTo("support@acme.com");
        // THE bug: this used to be the business key, which callers then fetched as a submission.
        assertThat(row.get("instanceId")).isNull();
        assertThat(row.get("definitionCode")).isNull();
    }

    @Test
    void aFormReviewTaskIsStillReportedAsFormWithItsSubmission() {
        start("FormSubmissionIntakeProcess", "SM-1001", Map.of(
                "formMetaData", "{\"instanceId\":\"inst-77\",\"formCode\":\"contact\"}"));

        Map<String, Object> row = rowNamed("Review Submission");

        assertThat(row.get("kind")).isEqualTo("form");
        assertThat(row.get("instanceId")).isEqualTo("inst-77");
        assertThat(row.get("definitionCode")).isEqualTo("contact");
        assertThat(row.get("businessKey")).isEqualTo("SM-1001");
        assertThat(row.get("emailMessageId")).isNull();
    }

    @Test
    void bothKindsCoexistInOneInboxPage() {
        start("EmailInboxProcess", "email-inbox-msg-2", Map.of(
                "emailMessageId", "msg-2", "emailTaskName", "Review: Invoice"));
        start("FormSubmissionIntakeProcess", "SM-1002", Map.of(
                "formMetaData", "{\"instanceId\":\"inst-78\",\"formCode\":\"contact\"}"));

        var items = inbox.list(TENANT, null, null, null, 0, 50).items();

        assertThat(items).hasSize(2);
        assertThat(items).extracting(m -> m.get("kind")).containsExactlyInAnyOrder("email", "form");
    }

    @Test
    void aTaskWithNoVariablesAtAllDegradesToAFormRowRatherThanFailing() {
        // e.g. an instance started by hand or by an older workflow. It must still list.
        start("EmailInboxProcess", "bare-1", Map.of());

        Map<String, Object> row = rowNamed("Review inbound email");

        assertThat(row.get("kind")).isEqualTo("form"); // no emailMessageId to discriminate on
        assertThat(row.get("businessKey")).isEqualTo("bare-1");
    }

    @Test
    void thePriorityAppliedByRoutingIsVisibleToTheInbox() {
        start("EmailInboxProcess", "email-inbox-msg-3", Map.of(
                "emailMessageId", "msg-3", "emailTaskName", "Review: Urgent", "emailPriority", 90));

        assertThat(rowNamed("Review: Urgent").get("priority")).isEqualTo(90);
    }
}
