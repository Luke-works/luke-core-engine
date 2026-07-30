package com.luke.engine.capability.form;

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
 * A website we have OBSERVED embedding a form — the answer to "where is this form live?".
 *
 * <p>Recorded from the {@code Referer} of the iframe's document request to {@code /embed/{token}}: for a
 * cross-origin iframe, browsers send the embedding page under the default
 * {@code strict-origin-when-cross-origin} policy, and they send the ORIGIN only, which is exactly the
 * datum we want and nothing more.
 *
 * <p><b>Observed, not authoritative.</b> A host page can suppress the header
 * ({@code Referrer-Policy: no-referrer}), in which case we simply never learn about that site; and the
 * header is client-supplied, so a caller could name an origin that isn't really embedding the form. So
 * this list is intelligence for the author, NOT a security control — the authoritative "who may frame
 * this form" remains {@link FormDefinition#getAllowedEmbedOrigins()}, enforced as CSP
 * {@code frame-ancestors} by the browser. Treating it as anything more would let an attacker's forged
 * Referer become a permission.
 *
 * <p>{@code renderCount} is SAMPLED, not exact: to keep a public unauthenticated page from doing a write
 * per iframe load, an origin already seen is only touched again once its {@code lastSeenAt} is older than
 * the recorder's throttle window. Read it as "roughly how busy", never as an analytics figure.
 */
@Entity
@Table(
    name = "luke_form_embed_sites",
    uniqueConstraints = @UniqueConstraint(name = "uq_embed_site", columnNames = {"tenantId", "formCode", "origin"}),
    indexes = @Index(name = "idx_embed_site_form", columnList = "tenantId,formCode")
)
public class FormEmbedSite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    /** The form's stable code (not its internal id), so the row survives anything that re-keys forms. */
    @Column(nullable = false)
    private String formCode;

    /** Scheme + host [+ port], e.g. {@code https://acme.com}. Never a full URL — we only ever get, and
     *  only ever want, the origin. */
    @Column(nullable = false, length = 255)
    private String origin;

    @Column(nullable = false)
    private LocalDateTime firstSeenAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    /** Sampled render count — see the class note. */
    @Column(nullable = false)
    private long renderCount = 1;

    public FormEmbedSite() {}

    public FormEmbedSite(String tenantId, String formCode, String origin) {
        this.tenantId = tenantId;
        this.formCode = formCode;
        this.origin = origin;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getFormCode() { return formCode; }
    public void setFormCode(String formCode) { this.formCode = formCode; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public long getRenderCount() { return renderCount; }
    public void setRenderCount(long renderCount) { this.renderCount = renderCount; }
}
