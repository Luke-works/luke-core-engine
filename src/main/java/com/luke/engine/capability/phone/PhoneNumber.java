package com.luke.engine.capability.phone;

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
 * A phone number a tenant owns in Vapi — either a Vapi-provided number or a BYO
 * Twilio/Telnyx/Vonage number imported into Vapi. We mirror Vapi's id and the
 * answering assistant so outbound calls can name a {@code phoneNumberId} and inbound
 * calls on this number route to the right assistant. The provider credentials for an
 * imported number stay in Vapi; we never store them here.
 *
 * <p>Core's currentSchema (Strategy A); tenant isolation by the {@code tenantId} column.
 */
@Entity
@Table(
    name = "luke_phone_numbers",
    uniqueConstraints = @UniqueConstraint(name = "uq_phonenumber_vapi", columnNames = {"vapiNumberId"}),
    indexes = {
        @Index(name = "idx_phonenumber_tenant", columnList = "tenantId"),
        @Index(name = "idx_phonenumber_vapi", columnList = "vapiNumberId")
    }
)
public class PhoneNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** Vapi's id for the number — used as {@code phoneNumberId} on outbound calls. */
    @Column(nullable = false)
    private String vapiNumberId;

    /** E.164 number, e.g. +14155551234. */
    @Column(nullable = false)
    private String number;

    /** Vapi provider: vapi | twilio | telnyx | vonage. */
    @Column(nullable = false, length = 24)
    private String provider;

    /** Human label for the number. */
    private String name;

    /** Assistant that answers inbound calls on this number (optional). */
    private String assistantId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public PhoneNumber() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getVapiNumberId() { return vapiNumberId; }
    public void setVapiNumberId(String vapiNumberId) { this.vapiNumberId = vapiNumberId; }

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAssistantId() { return assistantId; }
    public void setAssistantId(String assistantId) { this.assistantId = assistantId; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
