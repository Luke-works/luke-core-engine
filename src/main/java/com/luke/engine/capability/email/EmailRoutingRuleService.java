package com.luke.engine.capability.email;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns a tenant's inbound {@link EmailRoutingRule}s: CRUD, and the match that turns an arriving
 * message into a {@link Routing} decision.
 *
 * <p>Rules are evaluated in {@link EmailRoutingRule#getSortOrder()} order and the FIRST match
 * decides everything — see the entity javadoc for why actions don't accumulate. A rule scoped to
 * a box only applies to that box; an unscoped rule applies to all of them. When nothing matches
 * (the common case, and the case for every tenant that has authored no rules) {@link #resolve}
 * returns the default routing: one unassigned review task named after the subject.
 */
@Service
public class EmailRoutingRuleService {

    private static final Logger log = LoggerFactory.getLogger(EmailRoutingRuleService.class);

    /** Camunda task names are varchar(255); keep the generated one comfortably inside it. */
    private static final int TASK_NAME_MAX = 200;

    /** Fallback when a rule sets no name and the message has no subject. */
    static final String DEFAULT_TASK_NAME = "Review inbound email";

    private final EmailRoutingRuleRepository rules;
    private final EmailBoxRepository boxes;

    /** Compiled-pattern cache, keyed by pattern text + flags. Bounded by the rule count. */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    public EmailRoutingRuleService(EmailRoutingRuleRepository rules, EmailBoxRepository boxes) {
        this.rules = rules;
        this.boxes = boxes;
    }

    /** The message fields a rule can test. */
    public record Candidate(String from, String subject, String to, String body) {}

    /** What routing decided. {@code matchedRuleId} is null when no rule matched. */
    public record Routing(String taskName, String assignee, String candidateGroup, Integer priority,
                          String processKey, boolean suppressTask,
                          String matchedRuleId, String matchedRuleName) {}

    /** Create/update input. */
    public record RuleRequest(String boxId, String name, Boolean enabled, Integer sortOrder,
                              String matchField, String matchOperator, String matchValue,
                              Boolean caseSensitive, String actionAssignee, String actionCandidateGroup,
                              Integer actionPriority, String actionProcessKey, String actionTaskName,
                              Boolean actionSuppressTask) {}

    // ── read ─────────────────────────────────────────────────────────────────

    public List<EmailRoutingRule> list(String tenantId) {
        return rules.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantId);
    }

    // ── write ────────────────────────────────────────────────────────────────

    /** Hard cap per tenant: rules run on a public webhook, so the work per message is bounded. */
    private static final int MAX_RULES_PER_TENANT = 100;

    @Transactional
    public EmailRoutingRule create(String tenantId, RuleRequest req) {
        if (rules.countByTenantId(tenantId) >= MAX_RULES_PER_TENANT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A tenant may have at most " + MAX_RULES_PER_TENANT + " routing rules");
        }
        EmailRoutingRule rule = new EmailRoutingRule();
        rule.setId(UUID.randomUUID().toString());
        rule.setTenantId(tenantId);
        apply(tenantId, rule, req);
        return rules.save(rule);
    }

    @Transactional
    public EmailRoutingRule update(String tenantId, String id, RuleRequest req) {
        EmailRoutingRule rule = rules.findByIdAndTenantId(id, tenantId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Routing rule not found"));
        apply(tenantId, rule, req);
        return rules.save(rule);
    }

    @Transactional
    public void delete(String tenantId, String id) {
        EmailRoutingRule rule = rules.findByIdAndTenantId(id, tenantId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Routing rule not found"));
        rules.delete(rule);
    }

    /** Reorder in one shot — the UI drags rows, and order IS the semantics here. */
    @Transactional
    public List<EmailRoutingRule> reorder(String tenantId, List<String> orderedIds) {
        List<EmailRoutingRule> mine = rules.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantId);
        Map<String, EmailRoutingRule> byId = new java.util.LinkedHashMap<>();
        for (EmailRoutingRule r : mine) byId.put(r.getId(), r);

        int i = 0;
        java.util.Set<String> placed = new java.util.HashSet<>();
        for (String id : orderedIds) {
            EmailRoutingRule r = byId.get(id);
            // Silently ignore ids that aren't this tenant's — a stale drag must not 500, and
            // must certainly not reorder someone else's rules.
            if (r != null && placed.add(id)) r.setSortOrder(i++);
        }
        // Anything the caller left out keeps its relative order but is RENUMBERED after the
        // listed rules. Leaving it alone would let a stale position collide with a freshly
        // assigned one, and first-match-wins then depends on a tie-break nobody can see.
        for (EmailRoutingRule r : mine) {
            if (!placed.contains(r.getId())) r.setSortOrder(i++);
        }
        rules.saveAll(mine);
        return rules.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantId);
    }

    private void apply(String tenantId, EmailRoutingRule rule, RuleRequest req) {
        if (isBlank(req.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rule name is required");
        }
        if (isBlank(req.matchValue())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Match value is required");
        }
        EmailRoutingRule.Field field = parseEnum(EmailRoutingRule.Field.class, req.matchField(),
                EmailRoutingRule.Field.SUBJECT, "matchField");
        EmailRoutingRule.Operator operator = parseEnum(EmailRoutingRule.Operator.class, req.matchOperator(),
                EmailRoutingRule.Operator.CONTAINS, "matchOperator");

        // Reject an unusable regex at AUTHORING time. Otherwise the failure surfaces on a public
        // webhook, per message, where the only honest response is to ignore the rule silently.
        if (operator == EmailRoutingRule.Operator.REGEX) {
            try {
                Pattern.compile(req.matchValue());
            } catch (PatternSyntaxException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid regular expression: " + e.getDescription());
            }
        }

        String boxId = isBlank(req.boxId()) ? null : req.boxId().trim();
        if (boxId != null) {
            EmailBox box = boxes.findByIdAndTenantId(boxId, tenantId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown box"));
            if (!EmailBox.Direction.INBOUND.name().equals(box.getDirection())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Routing rules apply to INBOUND boxes only");
            }
        }

        Integer priority = req.actionPriority();
        if (priority != null && (priority < 0 || priority > 1000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Priority must be between 0 and 1000");
        }

        rule.setBoxId(boxId);
        rule.setName(req.name().trim());
        rule.setEnabled(req.enabled() == null || req.enabled());
        rule.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        rule.setMatchField(field.name());
        rule.setMatchOperator(operator.name());
        rule.setMatchValue(req.matchValue());
        rule.setCaseSensitive(Boolean.TRUE.equals(req.caseSensitive()));
        rule.setActionAssignee(trimToNull(req.actionAssignee()));
        rule.setActionCandidateGroup(trimToNull(req.actionCandidateGroup()));
        rule.setActionPriority(priority);
        rule.setActionProcessKey(trimToNull(req.actionProcessKey()));
        rule.setActionTaskName(trimToNull(req.actionTaskName()));
        rule.setActionSuppressTask(Boolean.TRUE.equals(req.actionSuppressTask()));
    }

    // ── match ────────────────────────────────────────────────────────────────

    /**
     * Decide how an arriving message is handled. Never throws: routing is best-effort on a
     * public webhook, and a broken rule must not cost the tenant the message.
     *
     * @param boxId the matched inbound box, or null when the recipient matched no box
     */
    public Routing resolve(String tenantId, String boxId, Candidate msg) {
        Routing fallback = new Routing(defaultTaskName(msg.subject()), null, null, null, null, false, null, null);
        try {
            for (EmailRoutingRule rule : rules.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantId)) {
                if (!rule.isEnabled()) continue;
                // A box-scoped rule only applies to that box. An unmatched recipient (boxId null)
                // is therefore only ever handled by unscoped rules.
                if (rule.getBoxId() != null && !rule.getBoxId().equals(boxId)) continue;
                if (!matches(rule, msg)) continue;

                String taskName = rule.getActionTaskName() != null
                        ? truncate(rule.getActionTaskName())
                        : defaultTaskName(msg.subject());
                return new Routing(taskName, rule.getActionAssignee(), rule.getActionCandidateGroup(),
                        rule.getActionPriority(), rule.getActionProcessKey(), rule.isActionSuppressTask(),
                        rule.getId(), rule.getName());
            }
        } catch (RuntimeException e) {
            log.warn("Email routing failed for tenant {} (using default routing): {}", tenantId, e.toString());
        }
        return fallback;
    }

    /** True when this rule's test passes for the message. Never throws. */
    boolean matches(EmailRoutingRule rule, Candidate msg) {
        EmailRoutingRule.Field field = parseEnumQuiet(EmailRoutingRule.Field.class, rule.getMatchField());
        EmailRoutingRule.Operator op = parseEnumQuiet(EmailRoutingRule.Operator.class, rule.getMatchOperator());
        if (field == null || op == null) return false;

        for (String subject : subjects(field, msg)) {
            if (subject != null && test(op, subject, rule.getMatchValue(), rule.isCaseSensitive())) return true;
        }
        return false;
    }

    /** The message part(s) a field selects. ANY tests all of them. */
    private static List<String> subjects(EmailRoutingRule.Field field, Candidate m) {
        return switch (field) {
            case FROM -> List.of(nz(m.from()));
            case SUBJECT -> List.of(nz(m.subject()));
            case TO -> List.of(nz(m.to()));
            case BODY -> List.of(nz(m.body()));
            case ANY -> List.of(nz(m.from()), nz(m.subject()), nz(m.to()), nz(m.body()));
        };
    }

    private boolean test(EmailRoutingRule.Operator op, String haystack, String needle, boolean caseSensitive) {
        if (needle == null) return false;
        if (op == EmailRoutingRule.Operator.REGEX) return regexMatches(haystack, needle, caseSensitive);
        String h = caseSensitive ? haystack : haystack.toLowerCase(Locale.ROOT);
        String n = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);
        return switch (op) {
            case CONTAINS -> h.contains(n);
            case EQUALS -> h.equals(n);
            case STARTS_WITH -> h.startsWith(n);
            case ENDS_WITH -> h.endsWith(n);
            case REGEX -> false; // handled above
        };
    }

    /**
     * Regex match with a hard step budget.
     *
     * <p>A tenant authors these, and they run on an UNAUTHENTICATED webhook against attacker-
     * controlled text — anyone who can email the box chooses the input. {@code Pattern} has no
     * timeout, so an evaluation that backtracks exponentially pins a request thread per message
     * until the pool is gone.
     *
     * <p><b>Measured on the JDK we run (21), not assumed.</b> The textbook shapes
     * ({@code (a+)+$}, {@code (x+x+)+y}, {@code (a|aa)+$}) are optimised away here and return in
     * under a millisecond — quoting them as the danger would be repeating folklore. What still
     * blows up is a <em>backreference</em> forcing true backtracking: {@code (a+)+\1b} against 30
     * 'a's takes ~35 seconds, 28 takes ~9, and each extra character roughly quadruples it. That
     * matters because the regex a tenant writes need not look hostile — "find a doubled word",
     * {@code \b(\w+)\s+\1\b}, is a backreference people copy from the internet routinely; the
     * attacker only has to supply the body.
     *
     * <p>{@link BudgetedCharSequence} bounds the matcher by counting character reads, which is
     * the portable way to stop it: the matcher cannot backtrack without reading characters, so a
     * read budget IS a work budget. It turns that 35-second evaluation into ~30ms while leaving
     * ordinary rules — and even a genuine match of the hostile pattern — untouched.
     *
     * <p>Exceeding the budget fails the match rather than the message. A rule that cannot be
     * evaluated in the budget is a rule the tenant needs to simplify, and dropping the mail
     * would be a far worse answer than not routing it.
     */
    private boolean regexMatches(String haystack, String pattern, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern p;
        try {
            p = patternCache.computeIfAbsent(flags + " " + pattern, k -> Pattern.compile(pattern, flags));
        } catch (PatternSyntaxException e) {
            return false; // authoring validates, but a row could predate that check
        }
        try {
            return p.matcher(new BudgetedCharSequence(haystack, REGEX_STEP_BUDGET)).find();
        } catch (BudgetExceededException e) {
            log.warn("Email routing regex exceeded its step budget and was skipped: {}", abbreviate(pattern));
            return false;
        }
    }

    /** Character reads a single regex evaluation may make. ~1M is far beyond any honest rule. */
    private static final int REGEX_STEP_BUDGET = 1_000_000;

    /** Thrown by {@link BudgetedCharSequence} when a match reads too much. */
    static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException() { super(null, null, false, false); } // no stack: it's control flow
    }

    /**
     * A CharSequence that counts {@code charAt} calls and aborts past a budget. Wrapping the
     * INPUT (not the pattern) is what makes this work against any pattern, including ones
     * written specifically to backtrack.
     */
    static final class BudgetedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final int budget;
        private int reads;

        BudgetedCharSequence(CharSequence delegate, int budget) {
            this.delegate = delegate;
            this.budget = budget;
        }

        @Override public int length() { return delegate.length(); }

        @Override public char charAt(int index) {
            if (++reads > budget) throw new BudgetExceededException();
            return delegate.charAt(index);
        }

        @Override public CharSequence subSequence(int start, int end) {
            return new BudgetedCharSequence(delegate.subSequence(start, end), budget - reads);
        }

        @Override public String toString() { return delegate.toString(); }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** "Review: &lt;subject&gt;", or the generic name when there is no subject. */
    static String defaultTaskName(String subject) {
        if (isBlank(subject)) return DEFAULT_TASK_NAME;
        return truncate("Review: " + subject.trim());
    }

    private static String truncate(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= TASK_NAME_MAX ? t : t.substring(0, TASK_NAME_MAX - 1) + "…";
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, String what) {
        if (isBlank(raw)) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported " + what + ": " + raw);
        }
    }

    private static <E extends Enum<E>> E parseEnumQuiet(Class<E> type, String raw) {
        if (isBlank(raw)) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String trimToNull(String s) { return isBlank(s) ? null : s.trim(); }

    /** Used by {@link EmailBoxService} when a box is removed, so its rules don't outlive it. */
    @Transactional
    public void deleteRulesForBox(String tenantId, String boxId) {
        rules.deleteByTenantIdAndBoxId(tenantId, boxId);
    }

    /** Exposed for the controller's box-scope validation. */
    Optional<EmailBox> box(String tenantId, String boxId) {
        return boxes.findByIdAndTenantId(boxId, tenantId);
    }
}
