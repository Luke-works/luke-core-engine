package com.luke.engine.capability.form;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One OTP challenge for an outbound form recipient — the code mailed to the preparer-asserted
 * recipient email to prove control before they may open the form. Mirrors {@code EmailVerification}:
 * the code is never stored in the clear (only a salted SHA-256 hash), expires, and is attempt-capped.
 * One active challenge per instance (re-requesting replaces it).
 */
@Entity
@Table(name = "luke_form_recipient_otp")
public class FormRecipientOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The FormInstance this challenge belongs to (unique — one active challenge per instance). */
    @Column(nullable = false, unique = true)
    private String instanceId;

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private String codeSalt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public FormRecipientOtp() {}

    public String getId() { return id; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    public String getCodeSalt() { return codeSalt; }
    public void setCodeSalt(String codeSalt) { this.codeSalt = codeSalt; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
