package com.luke.engine.capability.signature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * One recipient (signer) of a {@link SignatureInstance}: a real person bound to a definition
 * signer ROLE ({@code signerId}), with their own unguessable signing token + per-recipient state.
 * Public signing resolves a recipient by {@code signToken} (no tenant header). The drawn signature
 * PNG is held in the {@link DocumentStore} (only its key is kept here, never the bytes).
 */
@Entity
@Table(
    name = "luke_signature_recipients",
    uniqueConstraints = @UniqueConstraint(name = "uq_sigrcpt_token", columnNames = {"signToken"}),
    indexes = {
        @Index(name = "idx_sigrcpt_instance", columnList = "instanceId"),
        @Index(name = "idx_sigrcpt_token", columnList = "signToken")
    }
)
public class SignatureRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String instanceId;

    @Column(nullable = false)
    private String tenantId;

    /** The definition SignerRole.id this recipient fills. */
    @Column(nullable = false)
    private String signerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    /** Routing order copied from the role ("order" is reserved → mapped column). */
    @Column(name = "signing_order", nullable = false)
    private int signingOrder;

    @Column(length = 32)
    private String verify = "NONE";

    @Column(nullable = false, length = 32)
    private String state = SignatureInstanceStates.R_PENDING;

    /** Unguessable public signing token. */
    @Column(nullable = false)
    private String signToken;

    /** DocumentStore key of the captured signature PNG (set when signed). */
    private String signatureObjectKey;

    private LocalDateTime signedAt;

    public SignatureRecipient() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getSignerId() { return signerId; }
    public void setSignerId(String signerId) { this.signerId = signerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public int getSigningOrder() { return signingOrder; }
    public void setSigningOrder(int signingOrder) { this.signingOrder = signingOrder; }
    public String getVerify() { return verify; }
    public void setVerify(String verify) { this.verify = verify; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getSignToken() { return signToken; }
    public void setSignToken(String signToken) { this.signToken = signToken; }
    public String getSignatureObjectKey() { return signatureObjectKey; }
    public void setSignatureObjectKey(String signatureObjectKey) { this.signatureObjectKey = signatureObjectKey; }
    public LocalDateTime getSignedAt() { return signedAt; }
    public void setSignedAt(LocalDateTime signedAt) { this.signedAt = signedAt; }
}
