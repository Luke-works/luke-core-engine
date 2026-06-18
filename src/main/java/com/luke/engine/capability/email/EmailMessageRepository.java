package com.luke.engine.capability.email;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, String> {

    Optional<EmailMessage> findByIdAndTenantId(String id, String tenantId);

    List<EmailMessage> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<EmailMessage> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);
}
