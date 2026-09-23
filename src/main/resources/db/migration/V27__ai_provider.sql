-- Bring-your-own-key for the AI assistant. One table:
--   luke_ai_provider — the AI provider a workspace connected (id IS the tenantId).
--
-- The API KEY IS NOT HERE. It lives in luke_secrets under the name 'ai.provider-key', encrypted
-- with AES-256-GCM like every other tenant secret, so it inherits the existing master-key rotation
-- and never needs a second crypto path. This table holds only what is safe to show a human or to
-- decide on: which provider, which model, whether the key last worked, its last four characters,
-- and a SHA-256 fingerprint that tells a rotation from a re-save without storing the key twice.
--
-- Postgres profile only (ddl-auto=none there); H2/tests build this from the entity via ddl-auto.
-- Must stay faithful to AiProvider — PostgresSchemaValidationTest runs Hibernate validate against a
-- real Postgres + these migrations. Mirrored idempotently in beforeMigrate.sql.
create table if not exists luke_ai_provider (
    id varchar(255) not null,
    provider varchar(255) not null,
    model varchar(255),
    status varchar(255) not null,
    key_last4 varchar(8),
    key_fingerprint varchar(64),
    connected_by varchar(255),
    connected_at timestamp(6),
    verified_at timestamp(6),
    disconnected_at timestamp(6),
    last_error varchar(500),
    created_at timestamp(6) not null,
    updated_at timestamp(6),
    primary key (id)
);
