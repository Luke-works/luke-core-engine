package com.luke.engine.capability.secrets;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backs the {@link SecretStore} port with AES-256-GCM encryption in Postgres, and
 * adds the tenant-facing management operations used by {@link SecretsController}.
 *
 * <p>Two classes of secret share the table: SYSTEM (platform-written, e.g. Postmark
 * tokens) and TENANT (bring-your-own). The management methods operate ONLY on TENANT
 * secrets, so the API can never read, rotate, or delete a SYSTEM secret. Plaintext
 * leaves this service only through {@link #get} (in-process backend use).
 */
@Service
public class SecretsService implements SecretStore {

    private final SecretRepository repository;
    private final SecretCrypto crypto;

    public SecretsService(SecretRepository repository, SecretCrypto crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    /** Masked, non-sensitive view for the API (no ciphertext, no plaintext). */
    public record SecretView(String name, String description, String managedBy,
                             String maskedValue, int version, LocalDateTime updatedAt, LocalDateTime createdAt) {}

    /* ── SecretStore port (internal backend use) ────────────── */

    @Override
    public void put(String tenantId, String name, String value, String managedBy) {
        upsert(tenantId, name, value, managedBy, null, null);
    }

    @Override
    public Optional<String> get(String tenantId, String name) {
        return repository.findByTenantIdAndName(tenantId, name)
                .map(s -> crypto.decrypt(s.getCiphertext(), s.getIv(), s.getKeyId()));
    }

    @Override
    public boolean delete(String tenantId, String name) {
        Optional<Secret> existing = repository.findByTenantIdAndName(tenantId, name);
        existing.ifPresent(repository::delete);
        return existing.isPresent();
    }

    /* ── internal/service management (any managedBy) ────────── */

    /**
     * Create or overwrite a secret and return its masked view. Used by the internal
     * service-to-service API. Defaults to SYSTEM-managed (platform/service secret)
     * unless a managedBy is given. On overwrite, the existing class is preserved.
     */
    public SecretView store(String tenantId, String name, String value,
                            String managedBy, String description, String createdBy) {
        requireName(name);
        requireValue(value);
        String mb = (managedBy != null && !managedBy.isBlank()) ? managedBy : ManagedBy.SYSTEM;
        return view(upsert(tenantId, name, value, mb, description, createdBy));
    }

    /** All of a tenant's secrets (SYSTEM + TENANT), masked. For internal/admin listing. */
    public List<SecretView> listAll(String tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(this::view).toList();
    }

    /* ── tenant-facing management (TENANT secrets only) ─────── */

    /** Create a new tenant-managed secret. Conflicts if the name is already taken. */
    public SecretView create(String tenantId, String name, String value, String description, String createdBy) {
        requireName(name);
        requireValue(value);
        if (repository.existsByTenantIdAndName(tenantId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A secret named '" + name + "' already exists");
        }
        return view(upsert(tenantId, name, value, ManagedBy.TENANT, description, createdBy));
    }

    /** Replace the value of an existing tenant-managed secret (bumps version). */
    public SecretView rotate(String tenantId, String name, String value) {
        requireValue(value);
        Secret existing = requireTenantSecret(tenantId, name);
        return view(upsert(tenantId, name, value, ManagedBy.TENANT, existing.getDescription(), existing.getCreatedBy()));
    }

    /** List the tenant's own (TENANT-managed) secrets, masked. SYSTEM secrets are excluded. */
    public List<SecretView> list(String tenantId) {
        return repository.findByTenantIdAndManagedByOrderByCreatedAtDesc(tenantId, ManagedBy.TENANT)
                .stream().map(this::view).toList();
    }

    /** Delete a tenant-managed secret. Refuses (404) if it's SYSTEM-managed or absent. */
    public void deleteTenantSecret(String tenantId, String name) {
        requireTenantSecret(tenantId, name);
        repository.findByTenantIdAndName(tenantId, name).ifPresent(repository::delete);
    }

    /* ── helpers ────────────────────────────────────────────── */

    private Secret upsert(String tenantId, String name, String value, String managedBy,
                          String description, String createdBy) {
        Secret s = repository.findByTenantIdAndName(tenantId, name).orElseGet(Secret::new);
        boolean isNew = s.getId() == null;

        SecretCrypto.Encrypted enc = crypto.encrypt(value);
        s.setTenantId(tenantId);
        s.setName(name);
        s.setCiphertext(enc.ciphertext());
        s.setIv(enc.iv());
        s.setKeyId(enc.keyId());
        s.setLastFour(mask(value));
        // Preserve the class of an existing secret so an internal put can't reclassify
        // a tenant secret (and vice-versa); only set it when creating.
        if (isNew) {
            s.setManagedBy(managedBy);
            s.setVersion(1);
            if (createdBy != null) s.setCreatedBy(createdBy);
        } else {
            s.setVersion(s.getVersion() + 1);
        }
        if (description != null) s.setDescription(description);
        return repository.save(s);
    }

    /** Load a TENANT-managed secret or 404 — guards the API from touching SYSTEM secrets. */
    private Secret requireTenantSecret(String tenantId, String name) {
        Secret s = repository.findByTenantIdAndName(tenantId, name)
                .filter(x -> ManagedBy.TENANT.equals(x.getManagedBy()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such secret: " + name));
        return s;
    }

    private SecretView view(Secret s) {
        return new SecretView(s.getName(), s.getDescription(), s.getManagedBy(),
                maskedValue(s.getLastFour()), s.getVersion(), s.getUpdatedAt(), s.getCreatedAt());
    }

    /** Render the stored hint as "••••3a9f". */
    private static String maskedValue(String lastFour) {
        return "••••" + (lastFour != null ? lastFour : "");
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }

    private static void requireValue(String value) {
        if (value == null || value.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value is required");
    }
}
