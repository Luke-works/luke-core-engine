package com.luke.engine.capability.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link EmailRoutingRuleService} — matching semantics, ordering, scoping, authoring validation,
 * and the regex safety budget.
 */
@SpringBootTest
class EmailRoutingRuleServiceTest {

    private static final String TENANT = "EMAIL-RULES-TEST";

    @Autowired private EmailRoutingRuleService service;
    @Autowired private EmailRoutingRuleRepository rules;
    @Autowired private EmailBoxRepository boxes;

    @BeforeEach
    void clean() { cleanup(); }

    @AfterEach
    void cleanup() {
        rules.findByTenantIdOrderBySortOrderAscCreatedAtAsc(TENANT).forEach(rules::delete);
        boxes.findByTenantIdOrderByCreatedAtAsc(TENANT).forEach(b -> boxes.deleteById(b.getId()));
    }

    private static EmailRoutingRuleService.Candidate msg(String from, String subject, String to, String body) {
        return new EmailRoutingRuleService.Candidate(from, subject, to, body);
    }

    private EmailRoutingRule saved(EmailRoutingRule.Field field, EmailRoutingRule.Operator op,
            String value, boolean caseSensitive, int order, String name) {
        EmailRoutingRule r = new EmailRoutingRule();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(TENANT);
        r.setName(name);
        r.setSortOrder(order);
        r.setMatchField(field.name());
        r.setMatchOperator(op.name());
        r.setMatchValue(value);
        r.setCaseSensitive(caseSensitive);
        return rules.save(r);
    }

    // ── operators ────────────────────────────────────────────────────────────

    @Test
    void everyOperatorBehavesAsItsNameClaims() {
        var m = msg("billing@acme.com", "Invoice 42 attached", "support@x.com", "please pay");

        assertThat(service.matches(
                saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.CONTAINS, "invoice", false, 0, "c"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.FROM, EmailRoutingRule.Operator.EQUALS, "billing@acme.com", false, 0, "e"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.FROM, EmailRoutingRule.Operator.STARTS_WITH, "billing@", false, 0, "s"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.FROM, EmailRoutingRule.Operator.ENDS_WITH, "@acme.com", false, 0, "en"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.REGEX, "Invoice \\d+", false, 0, "r"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.CONTAINS, "receipt", false, 0, "no"), m))
                .isFalse();
    }

    @Test
    void matchingIsCaseInsensitiveUnlessAskedOtherwise() {
        var m = msg("Billing@Acme.com", "INVOICE 42", "to@x.com", "body");

        assertThat(service.matches(
                saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.CONTAINS, "invoice", false, 0, "i"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.CONTAINS, "invoice", true, 0, "cs"), m))
                .isFalse();
        // …including for regex, which uses flags rather than lowercasing.
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.REGEX, "^invoice", false, 0, "ri"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.REGEX, "^invoice", true, 0, "rcs"), m))
                .isFalse();
    }

    @Test
    void fieldSelectsWhichPartIsTestedAndAnyTestsAllOfThem() {
        var m = msg("jo@example.com", "hello", "support@x.com", "the word widget appears only here");

        assertThat(service.matches(
                saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.CONTAINS, "widget", false, 0, "s"), m))
                .isFalse();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.BODY, EmailRoutingRule.Operator.CONTAINS, "widget", false, 0, "b"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "widget", false, 0, "a"), m))
                .isTrue();
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.TO, EmailRoutingRule.Operator.CONTAINS, "support", false, 0, "t"), m))
                .isTrue();
    }

    @Test
    void nullMessageFieldsNeverThrow() {
        // A malformed inbound payload must not take the webhook down.
        var m = msg(null, null, null, null);
        assertThat(service.matches(
                saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "x", false, 0, "n"), m))
                .isFalse();
        assertThat(service.resolve(TENANT, null, m)).isNotNull();
    }

    // ── resolution ───────────────────────────────────────────────────────────

    @Test
    void withNoRulesTheDefaultRoutingNamesTheTaskAfterTheSubject() {
        var d = service.resolve(TENANT, "box-1", msg("jo@x.com", "Refund request", "s@x.com", "b"));
        assertThat(d.matchedRuleId()).isNull();
        assertThat(d.taskName()).isEqualTo("Review: Refund request");
        assertThat(d.suppressTask()).isFalse();
    }

    @Test
    void aSubjectlessMessageStillGetsAUsableTaskName() {
        var d = service.resolve(TENANT, "box-1", msg("jo@x.com", "  ", "s@x.com", "b"));
        assertThat(d.taskName()).isEqualTo(EmailRoutingRuleService.DEFAULT_TASK_NAME);
    }

    @Test
    void aVeryLongSubjectIsTruncatedToFitTheTaskNameColumn() {
        var d = service.resolve(TENANT, "box-1", msg("jo@x.com", "S".repeat(500), "s@x.com", "b"));
        assertThat(d.taskName()).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void disabledRulesAreSkipped() {
        EmailRoutingRule r = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "x", false, 0, "off");
        r.setEnabled(false);
        r.setActionSuppressTask(true);
        rules.save(r);

        var d = service.resolve(TENANT, "box-1", msg("x@x.com", "x", "x", "x"));
        assertThat(d.matchedRuleId()).isNull();
        assertThat(d.suppressTask()).isFalse();
    }

    @Test
    void sortOrderDecidesWhichRuleWins() {
        EmailRoutingRule second = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "x", false, 5, "second");
        second.setActionCandidateGroup("late");
        rules.save(second);

        EmailRoutingRule first = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "x", false, 1, "first");
        first.setActionCandidateGroup("early");
        rules.save(first);

        var d = service.resolve(TENANT, "box-1", msg("x@x.com", "x", "x", "x"));
        assertThat(d.matchedRuleName()).isEqualTo("first");
        assertThat(d.candidateGroup()).isEqualTo("early");
    }

    @Test
    void anUnscopedRuleAppliesToEveryBoxIncludingUnmatchedMail() {
        EmailRoutingRule r = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "x", false, 0, "all");
        r.setActionCandidateGroup("triage");
        rules.save(r);

        assertThat(service.resolve(TENANT, "box-a", msg("x@x.com", "x", "x", "x")).candidateGroup())
                .isEqualTo("triage");
        // boxId null = the recipient matched no box at all.
        assertThat(service.resolve(TENANT, null, msg("x@x.com", "x", "x", "x")).candidateGroup())
                .isEqualTo("triage");
    }

    @Test
    void aRuleWithAnUnparseableFieldIsIgnoredRatherThanFatal() {
        // Defends against a row written by an older/newer version of the app.
        EmailRoutingRule r = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "x", false, 0, "bad");
        r.setMatchField("NOT_A_FIELD");
        rules.save(r);

        var d = service.resolve(TENANT, "box-1", msg("x@x.com", "x", "x", "x"));
        assertThat(d.matchedRuleId()).isNull();
    }

    // ── regex safety ─────────────────────────────────────────────────────────

    /**
     * A BACKREFERENCE pattern, not the textbook {@code (a+)+$}.
     *
     * <p>This was measured before it was asserted. On the JDK we run (21) the classic shapes are
     * optimised away and return in under a millisecond, so a test built on one passes with the
     * guard removed and proves nothing. {@code (a+)+\1b} does still backtrack exponentially:
     * ~9s at 28 characters, ~35s at 30, and the 60 used here is far beyond reach. Verified by
     * disabling the budget and watching this test fail.
     *
     * <p>The scenario is not exotic. The tenant writes the rule and an attacker writes the body,
     * and backreference regexes ("find a doubled word") are ordinary things to copy.
     */
    @Test
    void aBacktrackingRegexIsAbandonedInsteadOfHangingTheWebhook() {
        EmailRoutingRule evil = saved(EmailRoutingRule.Field.BODY, EmailRoutingRule.Operator.REGEX,
                "(a+)+\\1b", false, 0, "evil");
        evil.setActionSuppressTask(true);
        rules.save(evil);

        var m = msg("x@x.com", "s", "t", "a".repeat(60));

        // Terminates promptly AND fails closed: no match, so the mail is still routed normally.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThat(service.matches(evil, m)).isFalse();
            assertThat(service.resolve(TENANT, "box-1", m).suppressTask()).isFalse();
        });
    }

    @Test
    void anOrdinaryRegexIsUnaffectedByTheBudget() {
        EmailRoutingRule r = saved(EmailRoutingRule.Field.SUBJECT, EmailRoutingRule.Operator.REGEX,
                "^\\[TICKET-\\d{1,6}\\]", false, 0, "ok");
        assertThat(service.matches(r, msg("x@x.com", "[TICKET-12345] printer on fire", "t", "b"))).isTrue();
        assertThat(service.matches(r, msg("x@x.com", "no ticket here", "t", "b"))).isFalse();
    }

    /** The budget must not turn a REAL match into a miss — it bounds work, not correctness. */
    @Test
    void aBackreferenceRuleStillMatchesWhenItGenuinelyShould() {
        EmailRoutingRule dupWord = saved(EmailRoutingRule.Field.BODY, EmailRoutingRule.Operator.REGEX,
                "\\b(\\w+)\\s+\\1\\b", false, 0, "doubled word");
        assertThat(service.matches(dupWord, msg("x@x.com", "s", "t", "the the quick brown fox"))).isTrue();
        assertThat(service.matches(dupWord, msg("x@x.com", "s", "t", "no repeats at all here"))).isFalse();
    }

    // ── authoring validation ─────────────────────────────────────────────────

    private EmailRoutingRuleService.RuleRequest req(String name, String field, String op, String value) {
        return new EmailRoutingRuleService.RuleRequest(null, name, true, 0, field, op, value,
                false, null, null, null, null, null, false);
    }

    @Test
    void anInvalidRegexIsRejectedWhenAuthoredNotWhenMailArrives() {
        assertThatThrownBy(() -> service.create(TENANT, req("bad", "SUBJECT", "REGEX", "([unclosed")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid regular expression");
    }

    @Test
    void nameAndValueAreRequiredAndUnknownEnumsAreRejected() {
        assertThatThrownBy(() -> service.create(TENANT, req("  ", "SUBJECT", "CONTAINS", "x")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("name is required");
        assertThatThrownBy(() -> service.create(TENANT, req("n", "SUBJECT", "CONTAINS", "  ")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Match value is required");
        assertThatThrownBy(() -> service.create(TENANT, req("n", "NOPE", "CONTAINS", "x")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("matchField");
        assertThatThrownBy(() -> service.create(TENANT, req("n", "SUBJECT", "NOPE", "x")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("matchOperator");
    }

    @Test
    void anOutOfRangePriorityIsRejected() {
        var bad = new EmailRoutingRuleService.RuleRequest(null, "n", true, 0, "SUBJECT", "CONTAINS", "x",
                false, null, null, 5000, null, null, false);
        assertThatThrownBy(() -> service.create(TENANT, bad))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Priority");
    }

    @Test
    void aRuleCannotBeScopedToAnotherTenantsBox() {
        EmailBox foreign = new EmailBox();
        foreign.setId(UUID.randomUUID().toString());
        foreign.setTenantId("SOMEONE-ELSE");
        foreign.setDirection(EmailBox.Direction.INBOUND.name());
        foreign.setAddress("theirs@elsewhere.com");
        boxes.save(foreign);
        try {
            var scoped = new EmailRoutingRuleService.RuleRequest(foreign.getId(), "n", true, 0,
                    "SUBJECT", "CONTAINS", "x", false, null, null, null, null, null, false);
            assertThatThrownBy(() -> service.create(TENANT, scoped))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Unknown box");
        } finally {
            boxes.deleteById(foreign.getId());
        }
    }

    @Test
    void aRuleCannotBeScopedToAnOutboundBox() {
        EmailBox out = new EmailBox();
        out.setId(UUID.randomUUID().toString());
        out.setTenantId(TENANT);
        out.setDirection(EmailBox.Direction.OUTBOUND.name());
        out.setAddress("sales@intake.lukeflow.com");
        boxes.save(out);

        var scoped = new EmailRoutingRuleService.RuleRequest(out.getId(), "n", true, 0,
                "SUBJECT", "CONTAINS", "x", false, null, null, null, null, null, false);
        assertThatThrownBy(() -> service.create(TENANT, scoped))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("INBOUND");
    }

    @Test
    void reorderRenumbersRulesTheCallerLeftOutInsteadOfLeavingThemToCollide() {
        // A partial list must not leave a stale position that ties with a freshly assigned one:
        // first-match-wins would then hinge on a tie-break nobody can see in the UI.
        EmailRoutingRule a = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "a", false, 0, "a");
        EmailRoutingRule b = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "b", false, 1, "b");
        EmailRoutingRule c = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "c", false, 2, "c");

        // Only c and a are listed; b is omitted.
        List<EmailRoutingRule> after = service.reorder(TENANT, List.of(c.getId(), a.getId()));

        assertThat(after).extracting(EmailRoutingRule::getName).containsExactly("c", "a", "b");
        assertThat(after).extracting(EmailRoutingRule::getSortOrder).containsExactly(0, 1, 2);
    }

    @Test
    void reorderIgnoresARepeatedId() {
        EmailRoutingRule a = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "a", false, 0, "a");
        EmailRoutingRule b = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "b", false, 1, "b");

        List<EmailRoutingRule> after = service.reorder(TENANT, List.of(b.getId(), b.getId(), a.getId()));

        assertThat(after).extracting(EmailRoutingRule::getName).containsExactly("b", "a");
        assertThat(after).extracting(EmailRoutingRule::getSortOrder).containsExactly(0, 1);
    }

    @Test
    void reorderRewritesPositionsAndIgnoresForeignIds() {
        EmailRoutingRule a = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "a", false, 0, "a");
        EmailRoutingRule b = saved(EmailRoutingRule.Field.ANY, EmailRoutingRule.Operator.CONTAINS, "b", false, 1, "b");

        List<EmailRoutingRule> after = service.reorder(TENANT,
                List.of(b.getId(), "not-mine-" + UUID.randomUUID(), a.getId()));

        assertThat(after).extracting(EmailRoutingRule::getName).containsExactly("b", "a");
    }

    @Test
    void updateAndDeleteAreTenantScoped() {
        EmailRoutingRule mine = service.create(TENANT, req("mine", "SUBJECT", "CONTAINS", "x"));

        assertThatThrownBy(() -> service.update("OTHER-TENANT", mine.getId(), req("hijack", "SUBJECT", "CONTAINS", "y")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.delete("OTHER-TENANT", mine.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");

        assertThat(rules.findByIdAndTenantId(mine.getId(), TENANT)).isPresent();
    }
}
