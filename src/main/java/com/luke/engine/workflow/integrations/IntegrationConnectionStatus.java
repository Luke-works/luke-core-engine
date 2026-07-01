package com.luke.engine.workflow.integrations;

/**
 * Lifecycle of a tenant's connection to a third-party app (via Nango).
 *
 * <ul>
 *   <li>{@code PENDING} — a connect session was created; the user hasn't finished authorizing.</li>
 *   <li>{@code ACTIVE} — authorized; tokens live in Nango, ready to use.</li>
 *   <li>{@code NEEDS_RECONNECT} — a token refresh failed; the user must re-authorize.</li>
 *   <li>{@code REVOKED} — disconnected (by us or the provider).</li>
 * </ul>
 */
public enum IntegrationConnectionStatus {
    PENDING,
    ACTIVE,
    NEEDS_RECONNECT,
    REVOKED
}
