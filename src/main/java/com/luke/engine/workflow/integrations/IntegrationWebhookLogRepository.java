package com.luke.engine.workflow.integrations;

import org.springframework.data.jpa.repository.JpaRepository;

/** Dedup ledger for inbound Nango webhooks (keyed by delivery id). */
public interface IntegrationWebhookLogRepository extends JpaRepository<IntegrationWebhookLog, String> {
}
