package com.luke.engine.capability.email;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailServerRepository extends JpaRepository<EmailServer, String> {

    Optional<EmailServer> findByTenantId(String tenantId);

    boolean existsByTenantId(String tenantId);

    /** Resolve an inbound webhook token (from {@code /api/public/email/inbound/{token}}) to its tenant's server. */
    Optional<EmailServer> findByInboundHookToken(String inboundHookToken);
}
