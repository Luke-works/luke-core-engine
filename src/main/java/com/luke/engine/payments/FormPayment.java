package com.luke.engine.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * The charge a form submission owes — one row per submission that takes a payment.
 *
 * <p>The amount here is the SERVER's (from {@link PaymentAmountResolver}) and is the only figure a
 * PaymentIntent is ever created with. The submission itself waits in {@code AWAITING_PAYMENT} until
 * this row reaches {@link #SUCCEEDED} — which only a Stripe-confirmed state can cause — and only then
 * is it released to its process and workflows.
 */
@Entity
@Table(name = "luke_form_payment", indexes = {
        @Index(name = "idx_form_payment_tenant", columnList = "tenant_id"),
        @Index(name = "idx_form_payment_status", columnList = "status, updated_at")
})
public class FormPayment {

    /** Row written with the submission; no PaymentIntent exists yet. */
    public static final String CREATING = "CREATING";
    /** Intent exists; waiting for the payer (or retrying after a decline). */
    public static final String REQUIRES_PAYMENT = "REQUIRES_PAYMENT";
    /** Stripe accepted the payment and will settle it asynchronously. */
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCEEDED = "SUCCEEDED";
    /**
     * The attempt ended before an intent was usable (creating it failed, or it was abandoned). If an
     * intent id was recorded anyway (a create that finished after the attempt ended), the reconciler
     * keeps checking it until it is cancelled at Stripe.
     */
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";
    /**
     * This platform lost access to the charge before it settled (the Stripe account was disconnected,
     * or the intent no longer exists in this mode). The submission was released; the real outcome is
     * only visible in the tenant's Stripe dashboard. Always audited as {@code payments.unresolved}.
     */
    public static final String UNRESOLVED = "UNRESOLVED";

    /** States a payer may still pay from. */
    public static final Set<String> PAYABLE = Set.of(REQUIRES_PAYMENT);
    /** States the reconciler watches (plus FAILED rows that carry an intent id). */
    public static final Set<String> OPEN = Set.of(CREATING, REQUIRES_PAYMENT, PROCESSING);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "instance_id", nullable = false, unique = true)
    private String instanceId;

    @Column(nullable = false)
    private String formCode;

    @Column(nullable = false)
    private int formVersion;

    /** EMBED or RESPOND — decides what happens to an abandoned submission. */
    @Column(nullable = false, length = 32)
    private String door;

    @Column(nullable = false)
    private String fieldKey;

    @Column(nullable = false)
    private String stripeAccountId;

    @Column(nullable = false)
    private boolean livemode;

    @Column(name = "intent_id", unique = true)
    private String intentId;

    @Column(nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    private Integer quantity;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(length = 1000)
    private String description;

    @Column(name = "status", nullable = false, length = 32)
    private String status = CREATING;

    @Column(length = 64)
    private String lastErrorCode;

    @Column(length = 500)
    private String lastErrorMessage;

    @Column(nullable = false)
    private long amountRefunded;

    /** Stripe reports a dispute (chargeback) on the charge. Kept once seen, even if the dispute is won. */
    @Column(nullable = false)
    private boolean disputed;

    /** Consecutive reconciler runs that failed to reach Stripe for this row; reset on success. */
    @Column(name = "reconcile_attempts", nullable = false)
    private int reconcileAttempts;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    private LocalDateTime paidAt;

    private LocalDateTime canceledAt;

    @Version
    private long version;

    /** When set, the next update stamps this instead of "now" (the reconciler schedules a retry with it). */
    @Transient
    private LocalDateTime nextUpdatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = nextUpdatedAt != null ? nextUpdatedAt : LocalDateTime.now();
        this.nextUpdatedAt = null;
    }

    /** Stamp {@code updatedAt} with {@code at} on this update — the reconciler's staleness key. */
    public void touchAt(LocalDateTime at) {
        this.nextUpdatedAt = at;
        this.updatedAt = at;
    }

    /** Whether Stripe may still move money on this row's intent (or it may still get one). */
    public boolean isUnsettled() {
        return OPEN.contains(status)
                || ((FAILED.equals(status) || UNRESOLVED.equals(status)) && intentId != null);
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getFormCode() { return formCode; }
    public void setFormCode(String formCode) { this.formCode = formCode; }
    public int getFormVersion() { return formVersion; }
    public void setFormVersion(int formVersion) { this.formVersion = formVersion; }
    public String getDoor() { return door; }
    public void setDoor(String door) { this.door = door; }
    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String fieldKey) { this.fieldKey = fieldKey; }
    public String getStripeAccountId() { return stripeAccountId; }
    public void setStripeAccountId(String stripeAccountId) { this.stripeAccountId = stripeAccountId; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean livemode) { this.livemode = livemode; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = truncate(description, 1000); }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = truncate(lastErrorCode, 64); }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = truncate(lastErrorMessage, 500); }
    public long getAmountRefunded() { return amountRefunded; }
    public void setAmountRefunded(long amountRefunded) { this.amountRefunded = amountRefunded; }
    public boolean isDisputed() { return disputed; }
    public void setDisputed(boolean disputed) { this.disputed = disputed; }
    public int getReconcileAttempts() { return reconcileAttempts; }
    public void setReconcileAttempts(int reconcileAttempts) { this.reconcileAttempts = reconcileAttempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getCanceledAt() { return canceledAt; }
    public void setCanceledAt(LocalDateTime canceledAt) { this.canceledAt = canceledAt; }
    public long getVersion() { return version; }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
