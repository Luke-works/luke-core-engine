package com.luke.engine.capability.form;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormSubmissionOutboxRepository extends JpaRepository<FormSubmissionOutbox, String> {

    /** Oldest-first queue drain. */
    List<FormSubmissionOutbox> findByStatusOrderByCreatedAtAsc(String status);

    /** Idempotency lookup: one row per submission (businessKey = form instance id). */
    Optional<FormSubmissionOutbox> findByBusinessKey(String businessKey);
}
