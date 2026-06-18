package com.luke.engine.capability.email;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, String> {

    /** The active (latest) challenge for a tenant in a given status. */
    Optional<EmailVerification> findFirstByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);

    /** The most recent challenge for a tenant, whatever its status. */
    Optional<EmailVerification> findFirstByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** All still-PENDING challenges for a tenant (to supersede on a new request). */
    List<EmailVerification> findByTenantIdAndStatus(String tenantId, String status);
}
