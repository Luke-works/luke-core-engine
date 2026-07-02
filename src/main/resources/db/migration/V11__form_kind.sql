-- Flyway V11 — form "kind" (inbound / outbound) + kind-specific config.
--
-- Adds three columns to luke_form_definitions:
--   • kind                 — INBOUND (embedded, public submissions) | OUTBOUND (prefilled + sent to a
--                            recipient, never embeddable). Chosen at creation. NOT NULL, default INBOUND.
--   • submission_handling  — inbound only: what happens to a submission (e.g. 'COLLECT'). NULL = undecided,
--                            which keeps the embed surface gated. Backfilled to 'COLLECT' for already-
--                            published inbound forms so existing embeds keep working.
--   • outbound_roles_json  — outbound only: JSON map fieldKey → role (PREPARER | RECIPIENT | EITHER).
--
-- Under the postgres profile Hibernate ddl-auto is `none`, so this migration is the source of truth;
-- it must stay faithful to FormDefinition. IF NOT EXISTS keeps re-runs harmless.

    alter table luke_form_definitions add column if not exists kind varchar(255);
    alter table luke_form_definitions add column if not exists submission_handling varchar(255);
    alter table luke_form_definitions add column if not exists outbound_roles_json text;

    -- Existing forms predate the concept: treat them as inbound, and keep any already-published
    -- inbound form embeddable by marking its submission handling as the implicit default.
    update luke_form_definitions set kind = 'INBOUND' where kind is null;
    update luke_form_definitions
       set submission_handling = 'COLLECT'
     where submission_handling is null and published_version is not null;

    alter table luke_form_definitions alter column kind set not null;
