-- Flyway V20 — SUBMISSION PROVENANCE + EMBED VERSION CONTROL + the observed embed-site registry.
--
-- Three related changes:
--
--   1. luke_form_instances gains submitted_ip / submitted_user_agent / submitted_via — the evidence a
--      completed form needs to be enforceable (who submitted, from where, through which door). Written
--      ONCE at submit by FormSubmissionService (the single choke point every door funnels through) and
--      never rewritten; also copied into the immutable formMetaData the process instance carries, so the
--      record survives retention purging of the instance row.
--      All three are NULLABLE: rows submitted before this existed genuinely have no provenance, and
--      inventing a value would be worse than admitting we don't know. An IP is PERSONAL DATA — it
--      inherits the instance's retention.
--
--   2. luke_form_definitions gains embed_version_mode / embed_version. AUTO (the default, and how embeds
--      have always behaved) resolves the published version on every request, so publishing reaches every
--      live embed with no change on the embedding website. PINNED holds fillers on embed_version until
--      the author explicitly updates it — which is what makes a legally-significant form safe to iterate.
--      Existing rows are backfilled to AUTO so behaviour is unchanged by this migration.
--
--   3. luke_form_embed_sites — the websites we have OBSERVED framing a form, learned from the Referer of
--      the iframe's request for /embed/{token}. Bounded per form and sampled (see FormEmbedSiteRecorder).
--      This is intelligence for the author, NOT a security control: the authoritative "who may frame
--      this form" is still luke_form_definitions.allowed_embed_origins, enforced as CSP frame-ancestors.
--
-- Under the postgres profile Hibernate ddl-auto is `none`, so this migration is the schema source of
-- truth and MUST stay faithful to the entities (PostgresSchemaValidationTest boots a real Postgres +
-- these migrations and runs Hibernate `validate`). Conventions mirror V18/V19: varchar(255) default
-- strings, timestamp(6) for LocalDateTime, bigint for long, snake_case via
-- CamelCaseToUnderscoresNamingStrategy, indexes declared separately.

    -- 1. submission provenance -------------------------------------------------
    alter table luke_form_instances add column if not exists submitted_ip varchar(45);
    alter table luke_form_instances add column if not exists submitted_user_agent varchar(512);
    alter table luke_form_instances add column if not exists submitted_via varchar(32);

    -- 2. embed version policy --------------------------------------------------
    -- NOT NULL with a default so existing rows land on AUTO rather than null (the entity is a non-null
    -- String and Hibernate `validate` expects a non-nullable column).
    alter table luke_form_definitions
        add column if not exists embed_version_mode varchar(255) default 'AUTO' not null;
    alter table luke_form_definitions add column if not exists embed_version integer;

    -- Belt-and-braces for an environment whose column was added by an earlier ddl-auto:update run.
    update luke_form_definitions set embed_version_mode = 'AUTO' where embed_version_mode is null;

    -- 3. observed embed sites --------------------------------------------------
    create table if not exists luke_form_embed_sites (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        -- the form's stable code, not its internal id
        form_code varchar(255) not null,
        -- scheme://host[:port], never a full URL
        origin varchar(255) not null,
        first_seen_at timestamp(6) not null,
        last_seen_at timestamp(6) not null,
        -- SAMPLED, not exact: an already-seen origin is only re-written once its last_seen_at is older
        -- than the recorder's throttle window, so a busy embed costs one write per window.
        render_count bigint not null,
        primary key (id),
        constraint uq_embed_site unique (tenant_id, form_code, origin)
    );

    create index if not exists idx_embed_site_form on luke_form_embed_sites (tenant_id, form_code);
