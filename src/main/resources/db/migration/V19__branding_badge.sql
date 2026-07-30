-- Flyway V19 — "Developed at Lukeflow" BADGE + the per-tenant plan that gates it.
--
-- Two changes:
--   1. luke_tenant_plan — per-tenant commercial plan (id IS the tenantId, like luke_phone_settings).
--      A tenant is FREE unless a row says PAID: the ABSENCE of a row is the free tier, so there is
--      exactly one representation of "not paying" and a rolled-back/deleted row can never silently
--      upgrade anyone. Written only by the operator-authenticated PUT /api/tenants/{id}/plan (and,
--      later, by the billing integration). See com.luke.engine.branding.TenantPlan.
--   2. luke_form_definitions.show_branding — the per-form option ("Add a 'Developed at Lukeflow' tag").
--      NOT NULL default TRUE, and every existing row is backfilled to TRUE: the badge is our
--      attribution on free tenants' public forms, so the safe default is ON everywhere. A free tenant's
--      stored value is irrelevant while they are free — BrandingPolicy forces the badge on regardless —
--      so this column only takes effect for PAID tenants.
--
-- The badge lives on the definition (not in the versioned schema) because it is presentation chrome,
-- not part of the form's data contract: toggling it takes effect on the live embed with no re-publish.
--
-- Under the postgres profile Hibernate ddl-auto is `none`, so this migration is the schema source of
-- truth and MUST stay faithful to the entities (PostgresSchemaValidationTest boots a real Postgres +
-- these migrations and runs Hibernate `validate`). Conventions mirror V18: varchar(255) strings,
-- timestamp(6) for LocalDateTime, snake_case via CamelCaseToUnderscoresNamingStrategy.

    create table if not exists luke_tenant_plan (
        -- the tenantId — one plan row per tenant
        id varchar(255) not null,
        -- FREE | PAID. Rows are only written for PAID; FREE is represented by no row at all.
        plan varchar(255) not null,
        -- operator note (who authorized it / contract reference)
        note varchar(255),
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        primary key (id)
    );

    -- NOT NULL with a default so existing rows land on TRUE rather than null (the entity is a
    -- primitive boolean and Hibernate `validate` expects a non-nullable column).
    alter table luke_form_definitions
        add column if not exists show_branding boolean default true not null;

    -- Belt-and-braces for a table whose column was added by an earlier ddl-auto:update run (dev/qa
    -- predate this migration): make sure no row is left null before the app reads it as a primitive.
    update luke_form_definitions set show_branding = true where show_branding is null;
