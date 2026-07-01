package com.luke.engine.workflow.integrations;

/**
 * The seam to Nango (https://nango.dev) — managed OAuth + connectors. Kept an
 * interface so {@link ConnectionService} is unit-testable without HTTP; the HTTP
 * implementation is {@link NangoApiClient}.
 *
 * <p>Nango holds the OAuth tokens; this engine only ever creates connect sessions
 * and, later, triggers actions/reads syncs by {@code connectionId}. The Nango secret
 * key is server-only and never leaves {@link NangoApiClient}.
 */
public interface NangoClient {

    /** A short-lived Connect session token handed to the browser to open Nango's Connect UI. */
    record ConnectSession(String token, String expiresAt) {}

    /**
     * Create a Connect session for a tenant end-user to authorize {@code providerConfigKey}.
     * Returns the session token the frontend feeds to {@code @nangohq/frontend}.
     */
    ConnectSession createConnectSession(String providerConfigKey, String endUserId,
            String endUserEmail, String organizationId);

    /** Revoke a connection in Nango (best-effort; never throws). */
    void deleteConnection(String providerConfigKey, String nangoConnectionId);

    /**
     * Trigger a Nango action for a connection (the outbound rail). Returns the action's
     * result payload. Throws a {@link NangoException} subtype classified by failure mode
     * (see {@link NangoExceptions}) so the executor can retry vs. route vs. reconnect.
     */
    java.util.Map<String, Object> triggerAction(String providerConfigKey, String nangoConnectionId,
            String action, java.util.Map<String, Object> input);
}
