package com.luke.engine.capability.phone;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped access to {@link PhoneCall} audit rows. Every finder is tenant-bound. */
public interface PhoneCallRepository extends JpaRepository<PhoneCall, String> {

    Optional<PhoneCall> findByIdAndTenantId(String id, String tenantId);

    /** Join key for incoming Vapi webhooks (the call may belong to any tenant). */
    Optional<PhoneCall> findByVapiCallId(String vapiCallId);

    Page<PhoneCall> findByTenantId(String tenantId, Pageable pageable);

    Page<PhoneCall> findByTenantIdAndStatus(String tenantId, String status, Pageable pageable);

    Page<PhoneCall> findByTenantIdAndDirection(String tenantId, String direction, Pageable pageable);
}
