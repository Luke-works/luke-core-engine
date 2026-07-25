package com.luke.engine.capability.form;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FormAuditEventRepository extends JpaRepository<FormAuditEvent, String> {

    /** Most-recent-first activity feed for a form. */
    List<FormAuditEvent> findByFormIdAndTenantIdOrderByAtDesc(String formId, String tenantId);

    /** Bulk delete on purge. Derived delete queries need their own transaction. */
    @Transactional
    void deleteByFormId(String formId);

    /* ── retention purge (#53): lifecycle events past the window (timestamp column is `at`) ── */

    long countByAtBefore(LocalDateTime cutoff);

    @Modifying
    @Query("delete from FormAuditEvent e where e.at < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Tenant-deletion cascade (#53): drop a deleted tenant's form lifecycle events. */
    @Modifying
    @Query("delete from FormAuditEvent e where e.tenantId = :tenantId")
    int deleteByTenant(@Param("tenantId") String tenantId);
}
