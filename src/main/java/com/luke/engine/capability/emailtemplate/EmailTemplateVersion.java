package com.luke.engine.capability.emailtemplate;

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
 * An immutable, checked-in version of an {@link EmailTemplate} — the editable source
 * ({@code doc} = EmailDoc JSON) that was pushed to Postmark. Created by "check in /
 * publish"; never updated. Mirrors {@link com.luke.engine.capability.form.FormVersion}.
 *
 * <p><b>No HTML column</b> — the rendered HTML is owned by Postmark and addressed by
 * {@code postmarkAlias}/{@code postmarkTemplateId}.
 */
@Entity
@Table(
    name = "luke_email_template_versions",
    uniqueConstraints = @UniqueConstraint(name = "uq_emailtplversion_tpl_version", columnNames = {"emailTemplateId", "version"}),
    indexes = @Index(name = "idx_emailtplversion_tpl", columnList = "emailTemplateId")
)
public class EmailTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** References {@link EmailTemplate#getId()}. */
    @Column(nullable = false)
    private String emailTemplateId;

    @Column(nullable = false)
    private int version;

    /** The editable source for this version (EmailDoc JSON), NOT the rendered HTML. */
    @Column(columnDefinition = "text", nullable = false)
    private String doc;

    private String subject;

    /** Postmark template alias this version was published under. */
    private String postmarkAlias;

    /** Postmark numeric template id returned by the upsert; null until pushed. */
    private Long postmarkTemplateId;

    private String checkedInBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime checkedInAt = LocalDateTime.now();

    public EmailTemplateVersion() {}

    public EmailTemplateVersion(String emailTemplateId, int version, String doc, String subject, String checkedInBy) {
        this.emailTemplateId = emailTemplateId;
        this.version = version;
        this.doc = doc;
        this.subject = subject;
        this.checkedInBy = checkedInBy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmailTemplateId() { return emailTemplateId; }
    public void setEmailTemplateId(String emailTemplateId) { this.emailTemplateId = emailTemplateId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getDoc() { return doc; }
    public void setDoc(String doc) { this.doc = doc; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getPostmarkAlias() { return postmarkAlias; }
    public void setPostmarkAlias(String postmarkAlias) { this.postmarkAlias = postmarkAlias; }

    public Long getPostmarkTemplateId() { return postmarkTemplateId; }
    public void setPostmarkTemplateId(Long postmarkTemplateId) { this.postmarkTemplateId = postmarkTemplateId; }

    public String getCheckedInBy() { return checkedInBy; }
    public void setCheckedInBy(String checkedInBy) { this.checkedInBy = checkedInBy; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }
}
