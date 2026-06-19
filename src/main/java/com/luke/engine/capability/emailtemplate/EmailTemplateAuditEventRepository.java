package com.luke.engine.capability.emailtemplate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface EmailTemplateAuditEventRepository extends JpaRepository<EmailTemplateAuditEvent, String> {

    /** Most-recent-first activity feed for a template. */
    List<EmailTemplateAuditEvent> findByEmailTemplateIdAndTenantIdOrderByAtDesc(String emailTemplateId, String tenantId);

    /** Bulk delete on purge. Derived delete queries need their own transaction. */
    @Transactional
    void deleteByEmailTemplateId(String emailTemplateId);
}
