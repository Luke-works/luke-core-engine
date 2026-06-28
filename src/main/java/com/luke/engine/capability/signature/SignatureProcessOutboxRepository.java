package com.luke.engine.capability.signature;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Outbox of pending Camunda starts. The core process service polls {@code findByState(QUEUED)}. */
public interface SignatureProcessOutboxRepository extends JpaRepository<SignatureProcessOutbox, String> {

    List<SignatureProcessOutbox> findByStateOrderByCreatedAtAsc(String state);

    void deleteByInstanceId(String instanceId);
}
