-- A workspace may connect SEVERAL AI providers.
--
-- luke_ai_provider began as one row per tenant (id = tenantId) with a single secret name,
-- 'ai.provider-key'. Connecting a second provider therefore overwrote the first one's key: a
-- workspace that had verified Groq and then added Gemini lost the Groq key outright, silently
-- and unrecoverably. Each provider now gets its own row and its own secret.
--
-- Every existing workspace keeps exactly what it had: its row becomes that provider's row and
-- is marked preferred (it is the only one), and its stored key is renamed to the per-provider
-- name so nothing has to be re-pasted.
--
-- Postgres profile only (ddl-auto=none there); H2/tests build from the entities via ddl-auto.
-- Must stay faithful to AiProvider — PostgresSchemaValidationTest runs Hibernate validate
-- against a real Postgres + these migrations. Mirrored idempotently in beforeMigrate.sql.

alter table luke_ai_provider add column if not exists tenant_id varchar(255);
alter table luke_ai_provider add column if not exists preferred boolean;

-- The old id WAS the tenant id.
update luke_ai_provider set tenant_id = id where tenant_id is null;
-- A workspace had at most one, so whatever it had is the one turns should default to.
update luke_ai_provider set preferred = true where preferred is null;

alter table luke_ai_provider alter column tenant_id set not null;
alter table luke_ai_provider alter column preferred set not null;

-- Re-key the row so a tenant can hold one per provider.
update luke_ai_provider set id = tenant_id || ':' || provider where id = tenant_id;

create unique index if not exists uq_ai_provider_tenant on luke_ai_provider (tenant_id, provider);
create index if not exists idx_ai_provider_tenant on luke_ai_provider (tenant_id);

-- Move each stored key to its provider-specific name, so a second provider cannot overwrite it.
update luke_secrets s
   set name = 'ai.provider-key.' || p.provider
  from luke_ai_provider p
 where p.tenant_id = s.tenant_id
   and s.name = 'ai.provider-key';
