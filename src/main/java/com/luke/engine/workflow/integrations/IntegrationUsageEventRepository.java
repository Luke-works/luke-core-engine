package com.luke.engine.workflow.integrations;

import org.springframework.data.jpa.repository.JpaRepository;

/** Store for metered {@link IntegrationUsageEvent}s (WF-11 emit site; WF-15 billing). */
public interface IntegrationUsageEventRepository extends JpaRepository<IntegrationUsageEvent, String> {

    boolean existsByIdempotencyKey(String idempotencyKey);
}
