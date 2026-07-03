package com.luke.engine.capability.phone;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Outbox of pending Camunda starts/closures for phone calls; the consumer polls by state. */
public interface PhoneCallProcessOutboxRepository extends JpaRepository<PhoneCallProcessOutbox, String> {

    List<PhoneCallProcessOutbox> findByStateOrderByCreatedAtAsc(String state);

    void deleteByCallId(String callId);
}
