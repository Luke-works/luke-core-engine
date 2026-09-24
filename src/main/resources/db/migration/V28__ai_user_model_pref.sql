-- One person's own model choice, within one workspace.
--
-- The KEY stays the workspace's (luke_ai_provider, V27); only the MODEL is per person, so two
-- people on the same account can run different models — cheap and fast for drafting a form,
-- capable for a tricky workflow. Server-side rather than in the browser so the choice follows
-- someone between devices.
--
-- Holds NO secret: the worst a leaked row reveals is which model somebody prefers.
--
-- Postgres profile only (ddl-auto=none there); H2/tests build this from the entity via ddl-auto.
-- Must stay faithful to AiUserPreference — PostgresSchemaValidationTest runs Hibernate validate
-- against a real Postgres + these migrations. Mirrored idempotently in beforeMigrate.sql.
create table if not exists luke_ai_user_pref (
    id varchar(255) not null,
    tenant_id varchar(255) not null,
    user_id varchar(255) not null,
    model varchar(255),
    created_at timestamp(6) not null,
    updated_at timestamp(6),
    primary key (id)
);

create unique index if not exists uq_ai_user_pref on luke_ai_user_pref (tenant_id, user_id);
create index if not exists idx_ai_user_pref_tenant on luke_ai_user_pref (tenant_id);
