package com.luke.engine.capability.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.workflow.EmailEventCorrelator;
import java.util.List;
import java.util.UUID;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.TaskService;
import org.finos.fluxnova.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * End-to-end intake against the REAL process engine: a Postmark payload goes in, and a review
 * task with the right name/assignment comes out.
 *
 * <p>The engine is real on purpose. Mocking {@code RuntimeService} would assert that we asked for
 * a process to start, which is the part that was never in doubt; what these tests are actually
 * for is that the BPMN deploys, the task listener reads the routing variables, and the task a
 * human sees carries them. Only {@link EmailEventCorrelator} is mocked — user-designed workflows
 * are a separate surface with their own tests, and it is verified as called, not skipped.
 */
@SpringBootTest
class InboundEmailIntakeServiceTest {

    private static final String TENANT = "EMAIL-INTAKE-TEST";
    private static final String BOX_ADDRESS = "support@intake.lukeflow.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private InboundEmailIntakeService intake;
    @Autowired private EmailInboxProcessDeployer deployer;
    @Autowired private EmailServerRepository servers;
    @Autowired private EmailBoxRepository boxes;
    @Autowired private EmailMessageRepository messages;
    @Autowired private InboundEmailRepository inbound;
    @Autowired private EmailRoutingRuleRepository rules;
    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private IdentityService identityService;

    @MockBean private EmailEventCorrelator correlator;

    private EmailServer server;
    private EmailBox box;

    @BeforeEach
    void seed() {
        cleanup();
        when(correlator.correlate(anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(0);

        server = new EmailServer();
        server.setTenantId(TENANT);
        server.setCompanySlug("intake");
        server.setSenderDomain("intake.lukeflow.com");
        server.setDefaultFrom("no-reply@intake.lukeflow.com");
        server.setPostmarkServerId(999L);
        server.setMessageStream("outbound");
        server.setStatus("ACTIVE");
        server.setInboundHookToken("tok-" + UUID.randomUUID());
        servers.save(server);

        box = newBox(BOX_ADDRESS);
        deployer.deployFor(TENANT);
    }

    private EmailBox newBox(String address) {
        EmailBox b = new EmailBox();
        b.setId(UUID.randomUUID().toString());
        b.setTenantId(TENANT);
        b.setDirection(EmailBox.Direction.INBOUND.name());
        b.setAddress(address);
        b.setLocalPart(address.substring(0, address.indexOf('@')));
        b.setRoutingKey(b.getLocalPart());
        b.setWorkflowTrigger(true);
        return boxes.save(b);
    }

    @AfterEach
    void cleanup() {
        for (var d : repositoryService.createDeploymentQuery().tenantIdIn(TENANT).list()) {
            repositoryService.deleteDeployment(d.getId(), true); // cascade instances + tasks
        }
        rules.findByTenantIdOrderBySortOrderAscCreatedAtAsc(TENANT).forEach(rules::delete);
        // Entity deletes, not the @Modifying bulk queries — those need an ambient transaction,
        // which a plain @AfterEach does not have (the production callers are @Transactional).
        messages.findByTenantIdOrderByCreatedAtDesc(TENANT).forEach(m -> {
            inbound.findByIdAndTenantId(m.getId(), TENANT).ifPresent(inbound::delete);
            messages.delete(m);
        });
        boxes.findByTenantIdOrderByCreatedAtAsc(TENANT).forEach(b -> boxes.deleteById(b.getId()));
        servers.findByTenantId(TENANT).ifPresent(servers::delete);
    }

    // ── payload ──────────────────────────────────────────────────────────────

    private JsonNode payload(String from, String subject, String textBody, String postmarkId) {
        try {
            return MAPPER.readTree("""
                {
                  "From": "%s",
                  "FromFull": { "Email": "%s", "Name": "Jo Bloggs" },
                  "To": "%s",
                  "ToFull": [ { "Email": "%s", "MailboxHash": "support" } ],
                  "OriginalRecipient": "%s",
                  "Cc": "cc@example.com",
                  "ReplyTo": "reply@example.com",
                  "Subject": "%s",
                  "MessageID": "%s",
                  "MailboxHash": "support",
                  "TextBody": "%s",
                  "HtmlBody": "<p>%s</p>",
                  "StrippedTextReply": "just the new bit",
                  "Headers": [
                    { "Name": "Message-ID", "Value": "<abc123@mail.example.com>" },
                    { "Name": "In-Reply-To", "Value": "<prev@mail.example.com>" }
                  ],
                  "Attachments": [
                    { "Name": "invoice.pdf", "ContentType": "application/pdf",
                      "ContentLength": 8, "Content": "QUJDREVGR0g=" }
                  ]
                }
                """.formatted(from, from, BOX_ADDRESS, BOX_ADDRESS, BOX_ADDRESS,
                              subject, postmarkId, textBody, textBody));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Task> tasks() {
        return taskService.createTaskQuery().tenantIdIn(TENANT).active().list();
    }

    // ── the headline behaviour ───────────────────────────────────────────────

    @Test
    void anEmailToAnInboundBoxBecomesAReviewTaskCarryingTheMessage() {
        var result = intake.intake(server, payload("jo@example.com", "Refund request", "I was charged twice", "pm-1"));

        assertThat(result.received()).isTrue();
        assertThat(result.matched()).isTrue();
        assertThat(result.duplicate()).isFalse();
        assertThat(result.taskCreated()).isTrue();

        // The envelope.
        EmailMessage msg = messages.findByIdAndTenantId(result.messageId(), TENANT).orElseThrow();
        assertThat(msg.getDirection()).isEqualTo("INBOUND");
        assertThat(msg.getStatus()).isEqualTo(EmailStatus.RECEIVED);
        assertThat(msg.getFromAddress()).isEqualTo("jo@example.com");
        assertThat(msg.getSubject()).isEqualTo("Refund request");

        // The content — the whole point. Before this, a reviewer was asked to review a body
        // nobody had kept.
        InboundEmail content = inbound.findByIdAndTenantId(result.messageId(), TENANT).orElseThrow();
        assertThat(content.getTextBody()).isEqualTo("I was charged twice");
        assertThat(content.getHtmlBody()).isEqualTo("<p>I was charged twice</p>");
        assertThat(content.getStrippedTextReply()).isEqualTo("just the new bit");
        assertThat(content.getFromName()).isEqualTo("Jo Bloggs");
        assertThat(content.getCcAddresses()).isEqualTo("cc@example.com");
        assertThat(content.getBoxId()).isEqualTo(box.getId());
        assertThat(content.getBoxAddress()).isEqualTo(BOX_ADDRESS);
        // Postmark's MessageID is its own; threading needs the RFC header out of Headers[].
        assertThat(content.getMessageIdHeader()).isEqualTo("<abc123@mail.example.com>");
        assertThat(content.getInReplyTo()).isEqualTo("<prev@mail.example.com>");

        // A real, open, human-visible task named after the subject.
        List<Task> open = tasks();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getName()).isEqualTo("Review: Refund request");

        verify(correlator).correlate(eq(TENANT), eq(BOX_ADDRESS), eq(result.messageId()),
                eq("jo@example.com"), eq("Refund request"), anyString());
    }

    @Test
    void attachmentsAreRecordedAsMetadataAndTheirBytesAreNotStored() {
        var result = intake.intake(server, payload("jo@example.com", "With a file", "see attached", "pm-att"));

        InboundEmail content = inbound.findByIdAndTenantId(result.messageId(), TENANT).orElseThrow();
        assertThat(content.getAttachmentCount()).isEqualTo(1);
        assertThat(content.getAttachments()).contains("invoice.pdf").contains("application/pdf");
        // The base64 payload must not have been persisted anywhere in the row.
        assertThat(content.getAttachments()).doesNotContain("QUJDREVGR0g=");
    }

    @Test
    void theProcessVariableCarriesTheEventWithoutTheAttachmentBytes() {
        // Camunda spills any string over 4000 chars into a byte array, so a raw payload with a
        // 10 MB base64 attachment became a 10 MB process variable, per message, forever.
        var result = intake.intake(server, payload("jo@example.com", "With a file", "see attached", "pm-var"));

        String pid = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey("email-inbox-" + result.messageId())
                .singleResult().getId();
        String event = (String) runtimeService.getVariable(pid, "emailEvent");

        assertThat(event).contains("invoice.pdf");     // the fact of the attachment survives
        assertThat(event).doesNotContain("QUJDREVGR0g="); // its bytes do not
    }

    @Test
    void aRedeliveredWebhookDoesNotCreateASecondTask() {
        var first = intake.intake(server, payload("jo@example.com", "Hello", "body", "pm-dup"));
        assertThat(first.duplicate()).isFalse();
        assertThat(tasks()).hasSize(1);

        var second = intake.intake(server, payload("jo@example.com", "Hello", "body", "pm-dup"));

        assertThat(second.duplicate()).isTrue();
        assertThat(second.taskCreated()).isFalse();
        assertThat(second.messageId()).isEqualTo(first.messageId()); // points at what we already hold
        assertThat(tasks()).hasSize(1);                              // and no duplicate human work
        assertThat(messages.findByTenantIdOrderByCreatedAtDesc(TENANT)).hasSize(1);
    }

    @Test
    void mailForAnUnknownRecipientIsStoredButMakesNoTask() {
        JsonNode p = payload("jo@example.com", "Stranger", "body", "pm-unknown");
        ((com.fasterxml.jackson.databind.node.ObjectNode) p).put("OriginalRecipient", "nobody@intake.lukeflow.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) p).put("MailboxHash", "");
        ((com.fasterxml.jackson.databind.node.ObjectNode) p).remove("ToFull");

        var result = intake.intake(server, p);

        assertThat(result.matched()).isFalse();
        assertThat(result.taskCreated()).isFalse();
        // Still stored: nothing a tenant was sent is silently dropped.
        assertThat(inbound.findByIdAndTenantId(result.messageId(), TENANT)).isPresent();
        assertThat(tasks()).isEmpty();
    }

    @Test
    void aBoxWithProcessingOffStoresTheMailAndStopsThere() {
        box.setWorkflowTrigger(false);
        boxes.save(box);

        var result = intake.intake(server, payload("jo@example.com", "Quiet", "body", "pm-quiet"));

        assertThat(result.matched()).isTrue();
        assertThat(result.taskCreated()).isFalse();
        assertThat(inbound.findByIdAndTenantId(result.messageId(), TENANT)).isPresent();
        assertThat(tasks()).isEmpty();
        verify(correlator, never()).correlate(any(), any(), any(), any(), any(), any());
    }

    // ── routing ──────────────────────────────────────────────────────────────

    private EmailRoutingRule rule(String name, EmailRoutingRule.Field field,
            EmailRoutingRule.Operator op, String value, int order) {
        EmailRoutingRule r = new EmailRoutingRule();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(TENANT);
        r.setName(name);
        r.setSortOrder(order);
        r.setMatchField(field.name());
        r.setMatchOperator(op.name());
        r.setMatchValue(value);
        return r;
    }

    @Test
    void aMatchingRuleAssignsPrioritisesAndRenamesTheTask() {
        EmailRoutingRule r = rule("Invoices", EmailRoutingRule.Field.SUBJECT,
                EmailRoutingRule.Operator.CONTAINS, "invoice", 0);
        r.setActionCandidateGroup("finance");
        r.setActionAssignee("workos:user_1");
        r.setActionPriority(75);
        r.setActionTaskName("Process invoice");
        rules.save(r);

        var result = intake.intake(server, payload("billing@acme.com", "Your Invoice 42", "due", "pm-rule"));

        assertThat(result.matchedRule()).isEqualTo("Invoices");
        Task t = tasks().get(0);
        assertThat(t.getName()).isEqualTo("Process invoice");
        assertThat(t.getAssignee()).isEqualTo("workos:user_1");
        assertThat(t.getPriority()).isEqualTo(75);
        assertThat(taskService.getIdentityLinksForTask(t.getId()))
                .anyMatch(l -> "finance".equals(l.getGroupId()));
    }

    @Test
    void aSuppressingRuleStoresTheMailWithoutMakingWork() {
        EmailRoutingRule r = rule("Bounces", EmailRoutingRule.Field.FROM,
                EmailRoutingRule.Operator.STARTS_WITH, "mailer-daemon@", 0);
        r.setActionSuppressTask(true);
        rules.save(r);

        var result = intake.intake(server,
                payload("mailer-daemon@example.com", "Undelivered Mail", "failure", "pm-bounce"));

        assertThat(result.matchedRule()).isEqualTo("Bounces");
        assertThat(result.taskCreated()).isFalse();
        assertThat(tasks()).isEmpty();
        assertThat(inbound.findByIdAndTenantId(result.messageId(), TENANT)).isPresent();
    }

    @Test
    void theFirstMatchingRuleWinsAndLaterOnesDoNotApply() {
        EmailRoutingRule first = rule("Urgent", EmailRoutingRule.Field.SUBJECT,
                EmailRoutingRule.Operator.CONTAINS, "urgent", 0);
        first.setActionCandidateGroup("oncall");
        rules.save(first);

        EmailRoutingRule second = rule("Everything else", EmailRoutingRule.Field.ANY,
                EmailRoutingRule.Operator.CONTAINS, "", 1);
        second.setActionCandidateGroup("general");
        second.setActionSuppressTask(true);
        rules.save(second);

        var result = intake.intake(server, payload("jo@example.com", "URGENT: outage", "help", "pm-order"));

        assertThat(result.matchedRule()).isEqualTo("Urgent");
        assertThat(tasks()).hasSize(1); // the later suppressing rule did NOT also apply
        assertThat(taskService.getIdentityLinksForTask(tasks().get(0).getId()))
                .anyMatch(l -> "oncall".equals(l.getGroupId()))
                .noneMatch(l -> "general".equals(l.getGroupId()));
    }

    @Test
    void aRuleScopedToAnotherBoxIsIgnored() {
        EmailBox other = newBox("sales@intake.lukeflow.com");
        EmailRoutingRule r = rule("Sales only", EmailRoutingRule.Field.ANY,
                EmailRoutingRule.Operator.CONTAINS, "quote", 0);
        r.setBoxId(other.getId());
        r.setActionSuppressTask(true);
        rules.save(r);

        // Arrives at support@, not sales@ — the rule must not fire even though the text matches.
        var result = intake.intake(server, payload("jo@example.com", "quote please", "quote", "pm-scope"));

        assertThat(result.matchedRule()).isNull();
        assertThat(tasks()).hasSize(1);
    }

    @Test
    void aRuleNamingAnUndeployedProcessLosesTheTaskButNeverTheMail() {
        EmailRoutingRule r = rule("To nowhere", EmailRoutingRule.Field.ANY,
                EmailRoutingRule.Operator.CONTAINS, "anything", 0);
        r.setActionProcessKey("NoSuchProcess");
        rules.save(r);

        var result = intake.intake(server, payload("jo@example.com", "anything", "anything", "pm-missing"));

        assertThat(result.received()).isTrue();
        assertThat(result.taskCreated()).isFalse();
        assertThat(inbound.findByIdAndTenantId(result.messageId(), TENANT)).isPresent();
    }

    @Test
    void theProcessVariableStaysPARSEABLEJsonNoMatterHowLongTheMailIs() throws Exception {
        // Shrinking this variable by substring would cut it mid-token, so every workflow that
        // parses it breaks on exactly the long messages a tenant most wants routed.
        String huge = "x".repeat(300_000);
        var result = intake.intake(server, payload("jo@example.com", "Huge", huge, "pm-json"));

        String pid = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey("email-inbox-" + result.messageId())
                .singleResult().getId();
        String event = (String) runtimeService.getVariable(pid, "emailEvent");

        assertThat(event.length()).isLessThanOrEqualTo(InboundEmailIntakeService.EVENT_VAR_MAX);

        // The parse IS the assertion: a variable cut mid-token throws here, and the test fails
        // with the actual JSON error rather than a boolean that hides it.
        JsonNode parsed = MAPPER.readTree(event);

        // The envelope survives even when the bodies do not, and says so.
        assertThat(parsed.path("Subject").asText()).isEqualTo("Huge");
        assertThat(parsed.path("emailBodyOmitted").asBoolean()).isTrue();
    }

    @Test
    void anOversizedBodyIsStoredTruncatedRatherThanWhole() {
        String huge = "x".repeat(InboundEmailIntakeService.BODY_MAX + 5_000);
        var result = intake.intake(server, payload("jo@example.com", "Big", huge, "pm-big"));

        InboundEmail content = inbound.findByIdAndTenantId(result.messageId(), TENANT).orElseThrow();
        assertThat(content.getTextBody()).hasSizeLessThan(huge.length());
        assertThat(content.getTextBody()).endsWith(InboundEmailIntakeService.TRUNCATION_MARKER);
    }

    @Test
    void identityScopeIsAlwaysReleased() {
        // The intake sets a tenant authentication to write engine-scoped data. Leaking it would
        // silently attach this tenant to whatever ran next on the thread.
        intake.intake(server, payload("jo@example.com", "Hello", "body", "pm-auth"));
        assertThat(identityService.getCurrentAuthentication()).isNull();
    }

    /** Ensures the mock's unused-stub strictness doesn't mask a missing call. */
    @Test
    void correlationIsAttemptedForEveryProcessedMessage() {
        intake.intake(server, payload("jo@example.com", "One", "body", "pm-c1"));
        intake.intake(server, payload("jo@example.com", "Two", "body", "pm-c2"));
        verify(correlator, org.mockito.Mockito.times(2))
                .correlate(anyString(), anyString(), anyString(), any(), any(), any());
    }

    /** With no rules authored — the state every tenant starts in — nothing changes shape. */
    @Test
    void withNoRulesTheTaskIsAnOrdinaryUnassignedReviewTask() {
        var result = intake.intake(server, payload("jo@example.com", "Plain", "body", "pm-plain"));
        assertThat(result.matchedRule()).isNull();

        Task t = tasks().get(0);
        assertThat(t.getName()).isEqualTo("Review: Plain");
        assertThat(t.getAssignee()).isNull();
        assertThat(taskService.getIdentityLinksForTask(t.getId())).isEmpty();
    }
}
