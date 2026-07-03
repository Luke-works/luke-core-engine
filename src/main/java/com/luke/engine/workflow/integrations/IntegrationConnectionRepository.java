package com.luke.engine.workflow.integrations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped access to {@link IntegrationConnection} rows. */
public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, String> {

    Optional<IntegrationConnection> findByIdAndTenantId(String id, String tenantId);

    List<IntegrationConnection> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** Live connections that count against a tenant's quota (everything not REVOKED). */
    long countByTenantIdAndStatusNot(String tenantId, IntegrationConnectionStatus status);

    /** The tenant's connection for a provider in a given status (e.g. the ACTIVE one to use at runtime). */
    Optional<IntegrationConnection> findFirstByTenantIdAndProviderKeyAndStatus(
            String tenantId, String providerKey, IntegrationConnectionStatus status);

    /** Resolve an inbound webhook (which carries Nango's connection id) back to our row + tenant. */
    Optional<IntegrationConnection> findFirstByNangoConnectionId(String nangoConnectionId);
}
