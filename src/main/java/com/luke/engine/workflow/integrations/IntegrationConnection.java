package com.luke.engine.workflow.integrations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A tenant's connection to a third-party app, brokered by Nango. This row is the
 * system of record for <em>which</em> connections exist; the OAuth tokens themselves
 * live in Nango and never touch this engine — we hold only the {@code nangoConnectionId}
 * handle.
 *
 * <p>The primary {@code id} is a random UUID minted by us at connect time (it is
 * echoed back through the connect session so the auth webhook, WF-10, can correlate
 * the authorized connection to this row). {@code nangoConnectionId} is populated once
 * Nango reports the connection active.
 *
 * <p>Tenant-scoped with an explicit {@code tenantId} column, mirroring
 * {@code FormDefinition}; queries always pass the tenant.
 */
@Entity
@Table(
    name = "luke_integration_connections",
    indexes = @Index(name = "idx_integration_conn_tenant", columnList = "tenantId")
)
public class IntegrationConnection {

    /** Our row id (a random UUID minted at connect time). Assigned by the service, not generated. */
    @Id
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** The connector, e.g. {@code "salesforce"} — Nango's {@code provider_config_key}. */
    @Column(nullable = false)
    private String providerKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntegrationConnectionStatus status = IntegrationConnectionStatus.PENDING;

    /** Nango's connection id, once authorized (null while PENDING). */
    private String nangoConnectionId;

    /** A human label for the connected account (e.g. the SF org name), once known. */
    private String externalAccount;

    /** Granted scopes, if reported. */
    @Column(columnDefinition = "text")
    private String scopes;

    /** Last error detail when NEEDS_RECONNECT. */
    @Column(columnDefinition = "text")
    private String errorState;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime lastUsedAt;

    public IntegrationConnection() {}

    public IntegrationConnection(String id, String tenantId, String providerKey, String createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.providerKey = providerKey;
        this.createdBy = createdBy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public IntegrationConnectionStatus getStatus() { return status; }
    public void setStatus(IntegrationConnectionStatus status) { this.status = status; }

    public String getNangoConnectionId() { return nangoConnectionId; }
    public void setNangoConnectionId(String nangoConnectionId) { this.nangoConnectionId = nangoConnectionId; }

    public String getExternalAccount() { return externalAccount; }
    public void setExternalAccount(String externalAccount) { this.externalAccount = externalAccount; }

    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }

    public String getErrorState() { return errorState; }
    public void setErrorState(String errorState) { this.errorState = errorState; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
