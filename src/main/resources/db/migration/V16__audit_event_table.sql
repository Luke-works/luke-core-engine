-- Flyway V16 — ADMIN AUDIT TRAIL: the luke_audit_event system-of-record table (#37).
--
-- A durable, queryable, APPEND-ONLY record of every privileged administrative mutation —
-- user/role/capability/candidate-group changes, org (tenant) creation, and account/tenant
-- deletions (OrgAdminController / OnboardingController / OrganizationController / AccountController).
-- Plain application logs are not a tamper-evident, queryable trail; this table is what a SOC 2 /
-- ISO 27001 style control (and incident forensics) reads. AuditService ALSO emits every event to the
-- `luke.audit` SLF4J logger, so a row is never lost even if this write fails.
--
-- This is DISTINCT from luke_signature_audit (V6) — that is the per-signature-request legal trail;
-- this is the cross-cutting admin-action trail keyed by actor + action + target.
--
-- Under the postgres profile Hibernate ddl-auto is `none`, so this migration is the schema source of
-- truth and MUST stay faithful to com.luke.engine.audit.AuditEvent (PostgresSchemaValidationTest boots
-- a real Postgres + this migration and runs Hibernate `validate` against the entity). Conventions
-- mirror V13__email_asset_table.sql: varchar(255) default strings, timestamp(6) for LocalDateTime,
-- snake_case columns via CamelCaseToUnderscoresNamingStrategy, indexes declared separately.
--
-- APPEND-ONLY: no code path updates or deletes a row (the entity columns are updatable=false and
-- AuditEventRepository exposes save + reads only, never delete). DB-level immutability (REVOKE
-- UPDATE/DELETE from the app role) is an ops hardening follow-up, tracked separately.

    create table if not exists luke_audit_event (
        id varchar(255) not null,
        created_at timestamp(6) not null,
        -- who: the resolved authenticated principal, and whether they acted as a platform operator
        actor_id varchar(255),
        actor_operator boolean not null,
        -- tenant scope (null for account-level actions that span tenants, e.g. account deletion)
        tenant_id varchar(255),
        -- what + on which target
        action varchar(100) not null,
        target_type varchar(64),
        target_id varchar(255),
        -- from where (best-effort client IP), and how (request method + path)
        source_ip varchar(255),
        request_method varchar(16),
        request_path varchar(512),
        -- ties the record to the server log line that holds the full request context
        correlation_id varchar(255),
        -- free-form JSON context (role/level/capability code/deleted tenants ...)
        detail varchar(4000),
        primary key (id)
    );

    create index if not exists idx_audit_tenant_created on luke_audit_event (tenant_id, created_at);
    create index if not exists idx_audit_created on luke_audit_event (created_at);
    create index if not exists idx_audit_actor on luke_audit_event (actor_id);
