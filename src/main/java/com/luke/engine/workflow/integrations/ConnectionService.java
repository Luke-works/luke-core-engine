package com.luke.engine.workflow.integrations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the tenant-facing integration-connection lifecycle (WF-8): mint a connect
 * session (quota-gated), list/get/disconnect connections, and the status transitions
 * the auth webhook drives (WF-10).
 *
 * <p>Flow: we create a {@code PENDING} row with a random id, ask Nango for a Connect
 * session token (passing our id as the end-user reference so the later auth webhook can
 * correlate), and return {@code (connectionId, token)}. The browser opens Nango's
 * Connect UI with the token; on success the webhook flips the row to {@code ACTIVE}.
 */
@Service
public class ConnectionService {

    private final IntegrationConnectionRepository connections;
    private final NangoClient nango;

    /** Max non-revoked connections per tenant (the quota gate; billing-relevant). */
    @Value("${luke.workflow.nango.max-connections-per-tenant:25}")
    private int maxConnectionsPerTenant;

    public ConnectionService(IntegrationConnectionRepository connections, NangoClient nango) {
        this.connections = connections;
        this.nango = nango;
    }

    /** The result of starting a connect flow: our row id + the short-lived Nango session token. */
    public record ConnectResult(String connectionId, String sessionToken, String expiresAt) {}

    /**
     * Start a connect flow for {@code providerKey}. Enforces the per-tenant quota, creates
     * a PENDING row, and returns a Nango Connect session token.
     */
    @Transactional
    public ConnectResult startConnect(String tenantId, String providerKey, String userId, String userEmail) {
        if (providerKey == null || providerKey.isBlank()) {
            throw new IntegrationException("providerKey is required");
        }
        long live = connections.countByTenantIdAndStatusNot(tenantId, IntegrationConnectionStatus.REVOKED);
        if (live >= maxConnectionsPerTenant) {
            throw new QuotaExceededException(
                    "Connection quota reached (" + maxConnectionsPerTenant + "). Disconnect an app or upgrade.");
        }

        String id = UUID.randomUUID().toString();
        IntegrationConnection conn = new IntegrationConnection(id, tenantId, providerKey, userId);
        connections.save(conn);

        // End-user reference = our row id, so the auth webhook (WF-10) correlates back to this row.
        NangoClient.ConnectSession session =
                nango.createConnectSession(providerKey, id, userEmail, tenantId);

        return new ConnectResult(id, session.token(), session.expiresAt());
    }

    @Transactional(readOnly = true)
    public List<IntegrationConnection> list(String tenantId) {
        return connections.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public IntegrationConnection get(String tenantId, String id) {
        return require(tenantId, id);
    }

    /** Disconnect: revoke in Nango (best-effort) and delete our row. */
    @Transactional
    public void disconnect(String tenantId, String id) {
        IntegrationConnection conn = require(tenantId, id);
        nango.deleteConnection(conn.getProviderKey(), conn.getNangoConnectionId());
        connections.delete(conn);
    }

    /* ── Webhook-driven transitions (used by WF-10) ─────────────────────────── */

    /** Mark a pending connection active once Nango reports authorization. */
    @Transactional
    public IntegrationConnection markActive(String id, String nangoConnectionId, String externalAccount, String scopes) {
        IntegrationConnection conn = connections.findById(id)
                .orElseThrow(() -> new IntegrationException("Unknown connection " + id));
        conn.setStatus(IntegrationConnectionStatus.ACTIVE);
        conn.setNangoConnectionId(nangoConnectionId);
        conn.setExternalAccount(externalAccount);
        conn.setScopes(scopes);
        conn.setErrorState(null);
        conn.setLastUsedAt(LocalDateTime.now());
        return connections.save(conn);
    }

    /** Flag a connection as needing re-authorization after a refresh failure. */
    @Transactional
    public IntegrationConnection markNeedsReconnect(String id, String error) {
        IntegrationConnection conn = connections.findById(id)
                .orElseThrow(() -> new IntegrationException("Unknown connection " + id));
        conn.setStatus(IntegrationConnectionStatus.NEEDS_RECONNECT);
        conn.setErrorState(error);
        return connections.save(conn);
    }

    private IntegrationConnection require(String tenantId, String id) {
        return connections.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IntegrationException("Connection not found"));
    }
}
