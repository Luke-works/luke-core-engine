package com.luke.engine.capability.emailtemplate;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, String> {

    Optional<EmailTemplate> findByIdAndTenantId(String id, String tenantId);

    Optional<EmailTemplate> findByTenantIdAndCode(String tenantId, String code);

    boolean existsByTenantIdAndCode(String tenantId, String code);

    // Live (non-trashed) listings
    List<EmailTemplate> findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId);

    List<EmailTemplate> findByTenantIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId, String status);

    // Trash
    List<EmailTemplate> findByTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(String tenantId);
}
