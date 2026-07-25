-- Flyway V17 — RETENTION (#53): index the timestamp columns the scheduled retention purge ranges
-- over, so the daily cutoff delete/anonymize/redact does an index range scan instead of a full table
-- scan as these PII/audit trails grow. Additive and index-only — no column or type changes, so it
-- does not affect the JPA-entity ↔ schema validation (Hibernate validates columns, not indexes).
--
-- Maps to the RetentionService queries: EmailMessage.createdAt, FormInstance.createdAt,
-- FormAuditEvent.at, EmailVerification.createdAt.

    create index if not exists idx_email_messages_created_at on luke_email_messages (created_at);
    create index if not exists idx_form_instances_created_at on luke_form_instances (created_at);
    create index if not exists idx_form_audit_at on luke_form_audit (at);
    create index if not exists idx_email_verifications_created_at on luke_email_verifications (created_at);
