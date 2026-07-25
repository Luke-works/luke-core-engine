package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.finos.fluxnova.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Surfaces an OVERDUE form-intake review (#46). Attached to the "Review Submission" user task via a
 * NON-INTERRUPTING boundary timer, so when the review SLA lapses (and on each recurrence) this fires
 * WITHOUT cancelling the task — the reviewer can still complete it — and records the breach.
 *
 * <p>Emits the {@code luke.forms.review.overdue} counter tagged by tenant (for alerting/observability)
 * and a WARN log, so an abandoned submission is no longer silently pending. Referenced from the BPMN
 * as {@code ${overdueReviewDelegate}}. Best-effort: never rethrows, so a metrics hiccup can't fault
 * the escalation branch.
 */
@Component("overdueReviewDelegate")
public class OverdueReviewDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(OverdueReviewDelegate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MeterRegistry metrics;

    public OverdueReviewDelegate(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    public void execute(DelegateExecution execution) {
        try {
            Object meta = execution.getVariable("formMetaData");
            String tenantId = field(meta, "tenantId");
            String instanceId = field(meta, "instanceId");
            String tenantTag = tenantId != null ? tenantId : "unknown";

            metrics.counter("luke.forms.review.overdue", "tenant", tenantTag).increment();
            log.warn("Form-intake review OVERDUE (SLA breached): tenant={} instance={} process={}",
                    tenantTag, instanceId, execution.getProcessInstanceId());
        } catch (Exception e) {
            log.warn("Overdue-review flag failed (process {} continues): {}",
                    execution.getProcessInstanceId(), e.getMessage());
        }
    }

    /** formMetaData is a Spin JSON object (or JSON string) — both render JSON via toString(). */
    private static String field(Object formMetaData, String key) {
        if (formMetaData == null) return null;
        try {
            JsonNode n = MAPPER.readTree(formMetaData.toString());
            return n.hasNonNull(key) ? n.get(key).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
