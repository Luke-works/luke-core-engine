package com.luke.engine.recipient;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalOtpRepository extends JpaRepository<PortalOtp, String> {

    /** The one active challenge for a recipient in a tenant (unique on tenant+email). */
    Optional<PortalOtp> findByTenantIdAndRecipientEmail(String tenantId, String recipientEmail);
}
