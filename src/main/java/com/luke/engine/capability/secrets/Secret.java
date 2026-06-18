package com.luke.engine.capability.secrets;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * An encrypted secret, scoped to a tenant and addressed by {@code name}. The value
 * is stored as AES-256-GCM {@code ciphertext} + {@code iv} + {@code keyId}; the
 * plaintext lives only in memory while a backend service uses it. Ciphertext/IV are
 * {@code @JsonIgnore} and there is no endpoint that decrypts to the caller — only
 * {@link #lastFour} (a masked hint) is ever shown.
 *
 * <p>Unique on (tenantId, name). Lives in the capability engine's own schema.
 */
@Entity
@Table(
    name = "luke_secrets",
    uniqueConstraints = @UniqueConstraint(name = "uq_secret_tenant_name", columnNames = {"tenantId", "name"}),
    indexes = {
        @Index(name = "idx_secret_tenant", columnList = "tenantId"),
        @Index(name = "idx_secret_managed", columnList = "managedBy")
    }
)
public class Secret {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Logical key, e.g. "postmark.server-token" or a tenant-chosen name. */
    @Column(nullable = false)
    private String name;

    @JsonIgnore
    @Column(nullable = false, columnDefinition = "text")
    private String ciphertext;

    @JsonIgnore
    @Column(nullable = false)
    private String iv;

    /** Which master key encrypted this row (for rotation). */
    @Column(nullable = false)
    private String keyId;

    /** SYSTEM or TENANT (see {@link ManagedBy}). */
    @Column(nullable = false)
    private String managedBy = ManagedBy.TENANT;

    /** Optional human description (tenant-managed secrets). */
    private String description;

    /** Last few chars of the plaintext, for a masked UI hint (e.g. "3a9f"). Not sensitive. */
    private String lastFour;

    /** Bumped on each rotation/overwrite. */
    @Column(nullable = false)
    private int version = 1;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public Secret() {}

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @JsonIgnore
    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }

    @JsonIgnore
    public String getIv() { return iv; }
    public void setIv(String iv) { this.iv = iv; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getManagedBy() { return managedBy; }
    public void setManagedBy(String managedBy) { this.managedBy = managedBy; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLastFour() { return lastFour; }
    public void setLastFour(String lastFour) { this.lastFour = lastFour; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
