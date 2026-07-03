package com.luke.engine.capability.phone;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped access to a tenant's Vapi {@link PhoneNumber}s. */
public interface PhoneNumberRepository extends JpaRepository<PhoneNumber, String> {

    List<PhoneNumber> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<PhoneNumber> findByIdAndTenantId(String id, String tenantId);

    Optional<PhoneNumber> findByTenantIdAndVapiNumberId(String tenantId, String vapiNumberId);

    /** Resolve a number across tenants — the join an inbound webhook uses to find the owning tenant. */
    Optional<PhoneNumber> findByVapiNumberId(String vapiNumberId);

    boolean existsByTenantIdAndVapiNumberId(String tenantId, String vapiNumberId);
}
