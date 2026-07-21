package com.luke.engine.capability.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A registered email address ("box") on a tenant's verified sender domain, in one of two
 * {@link Direction directions}:
 *
 * <ul>
 *   <li><b>OUTBOUND</b> — a send-<em>from</em> identity with its OWN Postmark message stream
 *       ({@code postmarkStreamId}), so each box has isolated stats/reputation.</li>
 *   <li><b>INBOUND</b> — a receive-<em>at</em> address. Mail arriving for it (via the public
 *       inbound webhook) is stored and, when {@code workflowTrigger} is set, correlated to a
 *       workflow ({@code email.inbound}). {@code routingKey} is the Postmark MailboxHash used
 *       for {@code +hash@inbound.postmarkapp.com} routing when the domain isn't MX'd.</li>
 * </ul>
 *
 * <p>One row per (tenant, direction, address). Tenant-scoped.
 */
@Entity
@Table(
    name = "luke_email_boxes",
    indexes = {
        @Index(name = "idx_emailbox_tenant_dir_addr", columnList = "tenantId,direction,address", unique = true),
        @Index(name = "idx_emailbox_tenant", columnList = "tenantId"),
        @Index(name = "idx_emailbox_routing", columnList = "tenantId,routingKey")
    }
)
public class EmailBox {

    public enum Direction { INBOUND, OUTBOUND }

    @Id
    private String id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String direction;

    /** Full address, e.g. {@code support@lukeform.lukeflow.com}. */
    @Column(nullable = false)
    private String address;

    /** Local part (e.g. {@code support}) — the part before {@code @}. */
    private String localPart;

    private String displayName;

    @Column(nullable = false)
    private String status = "ACTIVE";

    /** OUTBOUND only: the dedicated Postmark message-stream id created for this box. */
    private String postmarkStreamId;

    /** INBOUND only: Postmark MailboxHash for {@code +hash@inbound.postmarkapp.com} routing. */
    private String routingKey;

    /** INBOUND only: fire an {@code email.inbound} workflow correlation on receipt. */
    @Column(nullable = false)
    private boolean workflowTrigger = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public EmailBox() {}

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getLocalPart() { return localPart; }
    public void setLocalPart(String localPart) { this.localPart = localPart; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPostmarkStreamId() { return postmarkStreamId; }
    public void setPostmarkStreamId(String postmarkStreamId) { this.postmarkStreamId = postmarkStreamId; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

    public boolean isWorkflowTrigger() { return workflowTrigger; }
    public void setWorkflowTrigger(boolean workflowTrigger) { this.workflowTrigger = workflowTrigger; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
