package com.luke.engine.capability.email;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, String> {

    Optional<EmailMessage> findByIdAndTenantId(String id, String tenantId);

    List<EmailMessage> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<EmailMessage> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);

    // Paged variants (#52) — emails accrue monotonically per tenant, so the list is
    // bounded server-side via the Pageable instead of loading the whole history.
    org.springframework.data.domain.Page<EmailMessage> findByTenantId(
            String tenantId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<EmailMessage> findByTenantIdAndStatus(
            String tenantId, String status, org.springframework.data.domain.Pageable pageable);
}
