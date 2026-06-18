package com.luke.engine.capability.secrets;

/**
 * Who owns a secret. SYSTEM secrets are written by the platform (e.g. a tenant's
 * Postmark token) and are invisible/untouchable through the tenant API. TENANT
 * secrets are bring-your-own values a tenant admin manages via {@code /api/secrets}.
 */
public final class ManagedBy {

    public static final String SYSTEM = "SYSTEM";
    public static final String TENANT = "TENANT";

    private ManagedBy() {}
}
