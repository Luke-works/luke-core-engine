package com.luke.engine.capability.secrets;

import java.util.Optional;

/**
 * The secret-storage <em>port</em> — backend services depend on this, not on the
 * concrete store. Today it's backed by {@link SecretsService} (AES-256-GCM in
 * Postgres); a Vault / AWS SSM / Infisical adapter can replace it later by swapping
 * one bean, with no change to callers.
 *
 * <p>Reads return plaintext in-process for server-side use only; nothing here is
 * exposed over HTTP. Keys are scoped per tenant.
 */
public interface SecretStore {

    /** Create or overwrite a secret for {@code tenantId} under {@code name}. */
    void put(String tenantId, String name, String value, String managedBy);

    /** The decrypted value, or empty if there's no such secret. */
    Optional<String> get(String tenantId, String name);

    /** Remove a secret; returns whether one existed. */
    boolean delete(String tenantId, String name);
}
