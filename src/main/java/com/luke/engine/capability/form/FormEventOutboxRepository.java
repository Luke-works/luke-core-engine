package com.luke.engine.capability.form;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Forms→workflow inbound-event outbox; the consumer polls QUEUED rows to correlate. */
public interface FormEventOutboxRepository extends JpaRepository<FormEventOutbox, String> {

    List<FormEventOutbox> findByStateOrderByCreatedAtAsc(String state);
}
