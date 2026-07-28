-- Flyway V15 — Recipient PORTAL (per-tenant "authenticate once, see everything assigned to me").
--
-- The recipient hub is capability-agnostic (com.luke.engine.recipient); forms is its first provider.
-- Two things are needed:
--
--   1. FORMS-side: a cheap, indexed way to find "all open instances for this email" without parsing
--      the recipient JSON blob. We denormalise the email/phone off FormInstance.recipient into
--      columns (FormInstance.setRecipient keeps them in sync on every write) and backfill existing
--      rows. (These columns stay in luke_form_instances — they're forms data the provider queries.)
--   2. PORTAL-side: capability-agnostic auth challenges keyed by (tenant, email) rather than a single
--      instance — an OTP (email or, behind a seam, SMS) and a single-use magic link.
--
-- Mirrors V12 (per-instance recipient OTP). Postgres only (ddl-auto=none; Flyway runs under the
-- postgres profile — H2 unit tests get these columns from the JPA mapping instead).

    alter table luke_form_instances add column if not exists recipient_email varchar(320);
    alter table luke_form_instances add column if not exists recipient_phone varchar(40);

    -- Best-effort backfill from the existing recipient JSON (stored as text): pull the "email"
    -- member, lower-cased/trimmed. New rows are written denormalised by the application.
    update luke_form_instances
       set recipient_email = lower(trim(substring(recipient from '"email"\s*:\s*"([^"]*)"')))
     where recipient_email is null
       and recipient is not null
       and recipient ~ '"email"\s*:\s*"[^"]+"';

    update luke_form_instances
       set recipient_phone = trim(substring(recipient from '"phone"\s*:\s*"([^"]*)"'))
     where recipient_phone is null
       and recipient is not null
       and recipient ~ '"phone"\s*:\s*"[^"]+"';

    create index if not exists idx_forminstance_recipient_email
        on luke_form_instances (tenant_id, recipient_email);

-- One salted-hashed, expiring, attempt-capped OTP per (tenant, recipient email) — mailed or texted
-- to prove control before the portal session is minted. Channel is informational (verification only
-- checks the code). Re-requesting replaces the active challenge (unique on tenant+email).
    create table if not exists luke_portal_otp (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        recipient_email varchar(320) not null,
        channel varchar(16) not null,
        attempts integer not null,
        code_hash varchar(255) not null,
        code_salt varchar(255) not null,
        created_at timestamp(6) not null,
        expires_at timestamp(6) not null,
        primary key (id),
        constraint uq_portal_otp_tenant_email unique (tenant_id, recipient_email)
    );

-- Single-use magic link: a high-entropy token (SHA-256 stored, unsalted — 256 bits needs no salt),
-- looked up by hash on consume. Bounded to one active link per (tenant, email): issuing a new one
-- clears prior links for that pair.
    create table if not exists luke_portal_magic_link (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        recipient_email varchar(320) not null,
        token_hash varchar(255) not null unique,
        created_at timestamp(6) not null,
        expires_at timestamp(6) not null,
        consumed_at timestamp(6),
        primary key (id)
    );

    create index if not exists idx_portal_magic_tenant_email
        on luke_portal_magic_link (tenant_id, recipient_email);
