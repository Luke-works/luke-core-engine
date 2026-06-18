package com.luke.engine.capability.form;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface FormAuditEventRepository extends JpaRepository<FormAuditEvent, String> {

    /** Most-recent-first activity feed for a form. */
    List<FormAuditEvent> findByFormIdAndTenantIdOrderByAtDesc(String formId, String tenantId);

    /** Bulk delete on purge. Derived delete queries need their own transaction. */
    @Transactional
    void deleteByFormId(String formId);
}
