package com.luke.engine.capability.access;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@link AccessRequestOutbox}: starts the approval process for each QUEUED row and flips
 * it to PUBLISHED (recording the process instance on the request itself) or, once the retry
 * budget is spent, FAILED.
 *
 * <p>Mirrors {@code FormSubmissionOutboxConsumer}. Retries are safe because
 * {@link AccessApprovalProcessService#start} is idempotent on the business key.
 */
@Component
public class AccessRequestOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestOutboxConsumer.class);

    private final AccessRequestOutboxRepository outbox;
    private final AccessRequestRepository requests;
    private final AccessApprovalProcessService processService;

    @Value("${luke.access.outbox-enabled:true}")
    private boolean enabled;

    @Value("${luke.access.outbox-max-retries:10}")
    private int maxRetries;

    public AccessRequestOutboxConsumer(AccessRequestOutboxRepository outbox,
                                       AccessRequestRepository requests,
                                       AccessApprovalProcessService processService) {
        this.outbox = outbox;
        this.requests = requests;
        this.processService = processService;
    }

    @Scheduled(fixedDelayString = "${luke.access.outbox-poll-ms:2000}")
    public void drain() {
        if (!enabled) return;
        List<AccessRequestOutbox> queued = outbox.findByStatusOrderByCreatedAtAsc(AccessRequestOutbox.QUEUED);
        for (AccessRequestOutbox row : queued) {
            process(row);
        }
    }

    void process(AccessRequestOutbox row) {
        try {
            AccessRequest req = requests.findById(row.getAccessRequestId()).orElse(null);
            if (req == null) {
                // The request was hard-deleted before the process started — nothing to orchestrate.
                row.setStatus(AccessRequestOutbox.FAILED);
                row.setErrorMessage("access request no longer exists");
                outbox.save(row);
                return;
            }
            // A request already decided before we got to it must not spawn an approval task.
            // This is not only the cancel case: if the engine was down when an owner acted, the
            // controller's fallback path decides the request inline, and starting a process
            // afterwards would put an approval task on a request that is already granted.
            if (!AccessRequest.PENDING.equals(req.getStatus())) {
                row.setStatus(AccessRequestOutbox.PUBLISHED);
                row.setErrorMessage("request already " + req.getStatus() + " before start; no process needed");
                row.setPublishedAt(Instant.now());
                outbox.save(row);
                log.info("Skipping process start for {} — already {}", row.getAccessRequestId(), req.getStatus());
                return;
            }

            String pid = processService.start(req);

            row.setStatus(AccessRequestOutbox.PUBLISHED);
            row.setProcessInstanceId(pid);
            row.setPublishedAt(Instant.now());
            row.setErrorMessage(null);
            outbox.save(row);

            // Record the instance on the request so the approve/deny endpoints can find its task.
            req.setProcessInstanceId(pid);
            requests.save(req);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            row.setRetryCount(row.getRetryCount() + 1);
            row.setErrorMessage(msg);
            if (row.getRetryCount() >= maxRetries) {
                row.setStatus(AccessRequestOutbox.FAILED);
                outbox.save(row);
                log.warn("Access-request process start permanently FAILED after {} attempts for {} (tenant {}): {}",
                        row.getRetryCount(), row.getAccessRequestId(), row.getTenantId(), msg);
            } else {
                outbox.save(row);
                log.warn("Access-request process start failed (attempt {}/{}); will retry for {} (tenant {}): {}",
                        row.getRetryCount(), maxRetries, row.getAccessRequestId(), row.getTenantId(), msg);
            }
        }
    }
}
