package com.luke.engine.capability.access;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessRequestOutboxRepository extends JpaRepository<AccessRequestOutbox, String> {

    /** Oldest-first so requests are started in the order they were raised. */
    List<AccessRequestOutbox> findByStatusOrderByCreatedAtAsc(String status);

    Optional<AccessRequestOutbox> findByAccessRequestId(String accessRequestId);
}
