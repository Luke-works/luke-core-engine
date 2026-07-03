package com.luke.engine.workflow.integrations;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Inbound-event outbox; the consumer polls QUEUED rows to correlate. */
public interface IntegrationEventOutboxRepository extends JpaRepository<IntegrationEventOutbox, String> {

    List<IntegrationEventOutbox> findByStateOrderByCreatedAtAsc(String state);
}
