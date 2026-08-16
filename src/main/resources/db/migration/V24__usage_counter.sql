-- Phase 2a usage metering: per-tenant, per-metric, per-month tallies behind plan limits and metered
-- billing. One row per (tenant, metric, period); id is the deterministic composite tenant|metric|YYYY-MM.
-- Postgres profile only (ddl-auto=none there); H2/tests build this from the UsageCounter entity via
-- ddl-auto. Must stay faithful to the entity — PostgresSchemaValidationTest runs Hibernate validate
-- against a real Postgres + these migrations.
create table if not exists luke_usage_counter (
    id varchar(255) not null,
    tenant_id varchar(255) not null,
    metric varchar(255) not null,
    period varchar(255) not null,
    used_count bigint not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6),
    primary key (id)
);

create index if not exists idx_usage_tenant_period on luke_usage_counter (tenant_id, period);
